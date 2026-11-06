package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountCancelledEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.DispositionFailedEvent;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The end of a disposition's payment, and the saga when it does not happen (D63).
 *
 * <ul>
 *   <li><b>Completed</b>: the money arrived with evidence; the disposition is completed.</li>
 *   <li><b>Failed, and another attempt may work</b> (the link, an order the provider never registered, a route or a
 *       company missing): the next attempt is asked, a new order with the same instruction, after a wait. The
 *       disposition stays in flight.</li>
 *   <li><b>Failed for good</b> (a rule of the bank or the beneficiary, an impossible instruction, the attempts used
 *       up) or <b>returned</b> by the beneficiary's bank: compensated at once. The principal and the available credit
 *       go back, the plan is cancelled, and an installment credit whose only disbursement this was is cancelled;
 *       {@code credit-account-cancelled} lets charges, accounting and origination compensate what they booked.</li>
 * </ul>
 *
 * <p>Before, a failure only gave the principal back: the plan, the opening fee, the accrual and the account stayed
 * alive, and nothing ever asked again.
 */
@Service
@Transactional
public class DisbursementOutcomeService implements SettleDisbursementUseCase {

    private static final Logger log = LoggerFactory.getLogger(DisbursementOutcomeService.class);

    static final String DISPOSITION_FAILED = "DISPOSITION_FAILED";
    static final String DISPOSITION_RETURNED = "DISPOSITION_RETURNED";
    static final String DISBURSEMENT_FAILED = "DISBURSEMENT_FAILED";
    static final String DISBURSEMENT_RETURNED = "DISBURSEMENT_RETURNED";
    private static final String DEFAULT_CONCEPT = "DISPOSICION DE CREDITO";

    private enum Outcome { FAILED, RETURNED }

    private final CreditAccountRepository accounts;
    private final DispositionRepository dispositions;
    private final InstallmentRepository installments;
    private final BalanceEventRepository balanceEvents;
    private final CreditPortfolioEventPublisher publisher;
    private final int maxAttempts;
    private final List<Duration> waits;

    /**
     * @param maxAttempts how many attempts a payment gets in all
     * @param waits       the wait before the second attempt, the third…; the last one repeats
     */
    public DisbursementOutcomeService(CreditAccountRepository accounts, DispositionRepository dispositions,
                                      InstallmentRepository installments, BalanceEventRepository balanceEvents,
                                      CreditPortfolioEventPublisher publisher,
                                      @Value("${credit-portfolio.disbursement.retry.max-attempts:3}") int maxAttempts,
                                      @Value("${credit-portfolio.disbursement.retry.waits:PT1H,PT6H}") String waits) {
        this.accounts = accounts;
        this.dispositions = dispositions;
        this.installments = installments;
        this.balanceEvents = balanceEvents;
        this.publisher = publisher;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.waits = waits == null || waits.isBlank() ? List.of()
                : Arrays.stream(waits.split(",")).map(String::trim).map(Duration::parse).toList();
    }

    @Override
    public void onDisbursementCompleted(UUID dispositionId, int attempt, String externalRef) {
        Disposition disposition = dispositions.findById(dispositionId).orElse(null);
        if (disposition == null) {
            log.warn("disbursement.completed sin disposición conocida dispositionId={} — ignorado", dispositionId);
            return;
        }
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            log.info("disbursement.completed sobre disposición no-PROCESSING dispositionId={} status={} — idempotente",
                    dispositionId, disposition.getStatus());
            return;
        }
        if (disposition.isStale(attempt)) {
            // An attempt that had failed paid after all, while a later one is in flight: two payouts are possible.
            log.error("Desembolso completado por un intento anterior dispositionId={} intento={} en vuelo={}: "
                    + "revisar un posible pago doble", dispositionId, attempt, disposition.getAttempt());
        }

        disposition.complete(externalRef);
        dispositions.save(disposition);

        CreditAccount account = locked(disposition.getCreditAccountId()).orElse(null);
        if (account == null) {
            log.warn("disbursement.completed: cuenta ausente creditAccountId={} — disposición completada sin evento",
                    disposition.getCreditAccountId());
            return;
        }
        publisher.publishDispositionCompleted(new DispositionCompletedEvent(
                disposition.getDispositionId(), account.getCreditAccountId(), account.getObligorPartyId(),
                disposition.getAmount(), disposition.getDispositionType().name(),
                account.getAvailableCredit(), account.getBalanceVersion()));
        log.info("Disposition COMPLETED por disbursement dispositionId={} intento={} externalRef={}",
                dispositionId, attempt, externalRef);
    }

    @Override
    public void onDisbursementFailed(DisbursementFailure failure) {
        Disposition disposition = dispositions.findById(failure.dispositionId()).orElse(null);
        if (disposition == null) {
            log.warn("disbursement.failed sin disposición conocida dispositionId={} — ignorado", failure.dispositionId());
            return;
        }
        if (disposition.getStatus() != DispositionStatus.PROCESSING) {
            log.info("disbursement.failed sobre disposición no-PROCESSING dispositionId={} status={} — idempotente",
                    failure.dispositionId(), disposition.getStatus());
            return;
        }
        if (disposition.isStale(failure.attempt())) {
            log.info("disbursement.failed de un intento anterior dispositionId={} intento={} en vuelo={} — ignorado",
                    failure.dispositionId(), failure.attempt(), disposition.getAttempt());
            return;
        }
        if (failure.retryable() && disposition.getAttempt() < maxAttempts && disposition.hasPayee()) {
            retry(disposition, failure);
            return;
        }
        compensate(disposition, Outcome.FAILED, failure.failureCode(), failure.failureReason());
    }

    @Override
    public void onDisbursementReturned(UUID dispositionId, String reason) {
        Disposition disposition = dispositions.findById(dispositionId).orElse(null);
        if (disposition == null) {
            log.warn("disbursement.returned sin disposición conocida dispositionId={} — ignorado", dispositionId);
            return;
        }
        if (disposition.getStatus() == DispositionStatus.REVERSED || disposition.getStatus() == DispositionStatus.FAILED) {
            log.info("disbursement.returned sobre disposición ya compensada dispositionId={} status={} — idempotente",
                    dispositionId, disposition.getStatus());
            return;
        }
        compensate(disposition, Outcome.RETURNED, "RETURNED_BY_BENEFICIARY_BANK", reason);
    }

    /** The next attempt: a new order with the instruction the disposition kept, after the wait for that attempt. */
    private void retry(Disposition disposition, DisbursementFailure failure) {
        disposition.retry(failure.failureCode(), failure.failureReason());
        dispositions.save(disposition);
        CreditAccount account = accounts.findById(disposition.getCreditAccountId()).orElseThrow(() ->
                new IllegalStateException("Cuenta ausente para reintentar la disposición " + disposition.getDispositionId()));
        Instant notBefore = BusinessClock.current().now().plus(waitBefore(disposition.getAttempt()));
        publisher.publishDispositionAuthorized(new DispositionAuthorizedEvent(
                disposition.getDispositionId(), account.getCreditAccountId(), null, account.getOriginUnitCode(),
                disposition.getAmount(), "MXN", disposition.getPayeeName(), disposition.getPayeeAccount(),
                disposition.getPayeeAccountType(), disposition.getPayeeTaxId(),
                disposition.getPayeeConcept() != null ? disposition.getPayeeConcept() : DEFAULT_CONCEPT,
                disposition.getAttempt(), notBefore));
        log.warn("Desembolso reintentado dispositionId={} intento={} de {} sale a partir de {} motivo={} {}",
                disposition.getDispositionId(), disposition.getAttempt(), maxAttempts, notBefore,
                failure.failureCode(), failure.failureReason());
    }

    /** The wait before attempt {@code attempt} (2, 3…): the configured ones in order, the last repeating. */
    private Duration waitBefore(int attempt) {
        if (waits.isEmpty()) {
            return Duration.ZERO;
        }
        return waits.get(Math.min(Math.max(attempt - 2, 0), waits.size() - 1));
    }

    /**
     * The payment did not happen, for good: everything it moved goes back at once. The principal and the available
     * credit; the plan (its unpaid entries are cancelled, kept for the record); and an installment credit whose only
     * disbursement this was is cancelled, so the services that booked something for it compensate.
     */
    private void compensate(Disposition disposition, Outcome outcome, String failureCode, String reason) {
        if (outcome == Outcome.RETURNED) {
            disposition.reverse(reason);
        } else {
            disposition.fail(failureCode, reason);
        }
        dispositions.save(disposition);

        CreditAccount account = locked(disposition.getCreditAccountId()).orElse(null);
        if (account == null) {
            log.error("No se pudo compensar: cuenta ausente creditAccountId={} dispositionId={}",
                    disposition.getCreditAccountId(), disposition.getDispositionId());
            return;
        }

        String trigger = outcome == Outcome.RETURNED ? DISPOSITION_RETURNED : DISPOSITION_FAILED;
        // Keyed by what was undone and of which disposition: unique, and stable on a redelivery.
        String key = trigger + ":" + disposition.getDispositionId();
        BigDecimal amount = disposition.getAmount();
        account.revertDisposition(amount);
        boolean cancels = !account.isRevolving() && !account.getStatus().isTerminal();
        if (cancels) {
            account.cancel(outcome == Outcome.RETURNED ? DISBURSEMENT_RETURNED : DISBURSEMENT_FAILED);
        }
        accounts.save(account);

        balanceEvents.save(BalanceEvent.record(account.getCreditAccountId(), key, trigger, amount.negate(), account)
                .withBreakdown(amount.negate(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        int cancelledEntries = cancelPlan(account, disposition);

        // With the amount and the principal it moved, and its key: accounting books it from the fact, not from the
        // balance it remembers, and a stale delivery is not dropped.
        publisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                account.getCreditAccountId(), account.getObligorPartyId(),
                account.getPrincipalBalance(), account.getAccruedInterestBalance(),
                account.getPenaltyBalance(), account.getAvailableCredit(), account.getTotalDebt(),
                trigger, account.getStatus().name(), account.getBalanceVersion(), account.getOriginUnitCode(),
                null, amount.negate(), amount.negate(), BigDecimal.ZERO, BigDecimal.ZERO, null)
                .withSourceEventId(key));

        Instant now = BusinessClock.current().now();
        publisher.publishDispositionFailed(new DispositionFailedEvent(
                disposition.getDispositionId(), account.getCreditAccountId(), account.getObligorPartyId(),
                account.getProductType(), disposition.getDispositionType().name(), amount, outcome.name(),
                failureCode, reason, disposition.getAttempt(), cancels, account.getAvailableCredit(), now));
        if (cancels) {
            publisher.publishCreditAccountCancelled(new CreditAccountCancelledEvent(
                    account.getCreditAccountId(), account.getContractId(), account.getObligorPartyId(),
                    account.getProductType(), account.getProductCode(), account.getOriginUnitCode(),
                    disposition.getDispositionId(), amount, account.getCancellationReason(), failureCode, now));
        }
        log.warn("Desembolso {} y compensado dispositionId={} creditAccountId={} monto={} intentos={} motivo={} {}; "
                        + "cuotas canceladas={} crédito cancelado={}",
                outcome == Outcome.RETURNED ? "devuelto" : "fallido", disposition.getDispositionId(),
                account.getCreditAccountId(), amount, disposition.getAttempt(), failureCode, reason,
                cancelledEntries, cancels);
    }

    /** The plan of what was undone: the credit's own for an installment loan, the disposition's for a line. */
    private int cancelPlan(CreditAccount account, Disposition disposition) {
        UUID schedule = account.isRevolving() ? disposition.getDispositionId() : account.getCreditAccountId();
        List<Installment> plan = installments.findByScheduleId(schedule);
        int cancelled = 0;
        for (Installment installment : plan) {
            if (installment.cancel()) {
                cancelled++;
            }
        }
        if (cancelled > 0) {
            installments.saveAll(plan);
        }
        return cancelled;
    }

    /** The account, locked for this transaction and read after the lock (as every writer of an account does). */
    private Optional<CreditAccount> locked(UUID creditAccountId) {
        return accounts.lockAndLoad(creditAccountId).or(() -> accounts.findById(creditAccountId));
    }
}
