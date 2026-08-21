package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The balance engine (★). Charges/Payments/Collections never write portfolio balances —
 * they emit events; this service applies them to the single source of truth and republishes
 * {@code BalanceUpdated}.
 *
 * <p>Every mutation is idempotent (guarded by {@code sourceEventId} in balance_events) and
 * audited (one balance_events row per applied event).
 */
@Service
@Transactional
public class BalanceReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconciliationService.class);

    private final CreditAccountRepository accountRepository;
    private final BalanceEventRepository balanceEventRepository;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final InstallmentRepository installmentRepository;

    public BalanceReconciliationService(CreditAccountRepository accountRepository,
                                        BalanceEventRepository balanceEventRepository,
                                        CreditPortfolioEventPublisher eventPublisher,
                                        InstallmentRepository installmentRepository) {
        this.accountRepository      = accountRepository;
        this.balanceEventRepository = balanceEventRepository;
        this.eventPublisher         = eventPublisher;
        this.installmentRepository  = installmentRepository;
    }

    /**
     * charges.charge-applied → interest or penalty bucket per chargeType.
     * Publishes charge-rejected if the account is in a terminal state (post-check).
     */
    public void onChargeApplied(String sourceEventId, UUID accountId, String chargeType, BigDecimal amount) {
        onChargeApplied(sourceEventId, accountId, chargeType, amount, null);
    }

    /**
     * @param effectiveDate el día al que pertenece el cargo; {@code null} = ahora.
     *
     * <p>Se propaga tal cual al {@code balance-updated}: contabilidad deriva de ahí el período, así
     * que un devengo con fecha de junio se asienta en junio aunque se procese hoy.
     */
    public void onChargeApplied(String sourceEventId, UUID accountId, String chargeType,
                                BigDecimal amount, java.time.LocalDate effectiveDate) {
        if (sourceEventId != null && balanceEventRepository.existsBySourceEventId(sourceEventId)) {
            log.info("Duplicate charge event sourceEventId={} — skipping (idempotent)", sourceEventId);
            return;
        }
        CreditAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(accountId.toString()));

        if (account.getStatus() == com.fintech.creditportfolio.domain.CreditAccountStatus.WRITTEN_OFF
                || account.getStatus() == com.fintech.creditportfolio.domain.CreditAccountStatus.CLOSED) {
            log.warn("Charge rejected: account {} is in terminal state {} — chargeType={} amount={}",
                    accountId, account.getStatus(), chargeType, amount);
            eventPublisher.publishChargeRejected(sourceEventId, accountId, chargeType, amount,
                    "TERMINAL_ACCOUNT: status=" + account.getStatus());
            return;
        }
        apply(sourceEventId, accountId, "CHARGE_" + chargeType, amount, acc -> {
            if ("ORDINARY_INTEREST".equalsIgnoreCase(chargeType)) {
                acc.applyInterestAccrual(amount);
            } else if ("IVA".equalsIgnoreCase(chargeType)) {
                // El IVA iba a `penaltyBalance` junto con la mora y las comisiones, así que un
                // crédito al corriente mostraba «Penalización» que en realidad eran impuestos.
                acc.applyIvaCharge(amount);
            } else {
                acc.applyPenaltyCharge(amount);   // moratorium + all fees
            }
        }, effectiveDate);
    }

    /** charges.charge-reversed (reversal or waiver) → reverse from the original bucket. */
    public void onChargeReversed(String sourceEventId, UUID accountId, String originalChargeType,
                                 BigDecimal amount, boolean waived) {
        apply(sourceEventId, accountId, waived ? "CHARGE_WAIVED" : "CHARGE_REVERSED",
                amount.negate(), account -> account.reverseCharge(originalChargeType, amount));
    }

    /**
     * payments.payment-applied → reduce in hierarchy; settle if cleared.
     * Rejects (publishes payment-rejected) if amount exceeds totalDebt to prevent overpayment.
     */
    public void onPaymentApplied(String sourceEventId, UUID accountId, BigDecimal amount) {
        if (sourceEventId != null && balanceEventRepository.existsBySourceEventId(sourceEventId)) {
            log.info("Duplicate payment event sourceEventId={} — skipping (idempotent)", sourceEventId);
            return;
        }
        CreditAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(accountId.toString()));

        if (amount.compareTo(account.getTotalDebt()) > 0) {
            log.warn("Payment rejected: amount={} exceeds totalDebt={} for accountId={}",
                    amount, account.getTotalDebt(), accountId);
            eventPublisher.publishPaymentRejected(sourceEventId, accountId, amount,
                    "OVERPAYMENT: amount exceeds totalDebt " + account.getTotalDebt());
            return;
        }
        apply(sourceEventId, accountId, "PAYMENT_APPLIED", amount.negate(), acc -> {
            acc.applyPayment(amount);
            acc.settleIfClear();
        });
        applyToSchedule(accountId, amount);
    }

    /**
     * Aplica el pago al calendario, de la mensualidad más vieja a la más nueva.
     *
     * <p>Sin esto el pago bajaba el saldo y el plan seguía diciendo "pago 0 de
     * 12": el adeudo y el calendario se iban separando, y el seguimiento del
     * backoffice —y el contador del home— quedaban mintiendo.
     *
     * <p>Se cobra primero lo más viejo, que es la regla de aplicación de pagos y
     * lo que el cliente espera. Un excedente sigue a la siguiente; lo que sobre
     * después de cubrir todo el plan simplemente no se asigna —queda reflejado
     * en el saldo, que ya bajó—.
     *
     * <p>No falla el pago si el calendario no está: un revolvente no tiene, y
     * el abono al saldo ya quedó registrado.
     */
    private void applyToSchedule(UUID accountId, BigDecimal amount) {
        try {
            var schedule = installmentRepository.findByScheduleIdOrdered(accountId);
            if (schedule.isEmpty()) return;

            BigDecimal remaining = amount;
            var touched = new java.util.ArrayList<com.fintech.creditportfolio.domain.Installment>();
            for (var installment : schedule) {
                if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
                if (installment.getStatus() == InstallmentStatus.PAID) continue;
                BigDecimal before = remaining;
                remaining = installment.applyPayment(remaining);
                if (remaining.compareTo(before) != 0) touched.add(installment);
            }
            if (!touched.isEmpty()) {
                installmentRepository.saveAll(touched);
                log.info("Payment applied to schedule accountId={} installments={}",
                        accountId, touched.size());
            }
        } catch (Exception ex) {
            // El saldo ya se ajustó: que el calendario no se pueda actualizar no
            // debe deshacer un pago que el cliente ya hizo.
            log.error("No se pudo aplicar el pago al calendario accountId={}: {}",
                    accountId, ex.getMessage());
        }
    }

    /**
     * payments.payment-returned → restore debt that the returned payment had cleared.
     * Simplified: restores to principal (refined when payments-service carries the split).
     */
    public void onPaymentReturned(String sourceEventId, UUID accountId, BigDecimal amount) {
        apply(sourceEventId, accountId, "PAYMENT_RETURNED", amount, account ->
                account.applyPenaltyCharge(amount));   // restore as outstanding; precise bucket TBD
    }

    /** collections.write-off-executed → all balances to zero, terminal WRITTEN_OFF. */
    public void onWriteOffExecuted(String sourceEventId, UUID accountId) {
        apply(sourceEventId, accountId, "WRITE_OFF", BigDecimal.ZERO, CreditAccount::executeWriteOff);
    }

    // ── Core: idempotent apply + audit + publish ─────────────────────────────

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation) {
        apply(sourceEventId, accountId, eventType, delta, mutation, null);
    }

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation,
                       java.time.LocalDate effectiveDate) {

        if (sourceEventId != null && balanceEventRepository.existsBySourceEventId(sourceEventId)) {
            log.info("Duplicate balance event sourceEventId={} — skipping (idempotent)", sourceEventId);
            return;
        }

        CreditAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(accountId.toString()));

        // El desglose se mide aquí, entre el antes y el después de la mutación. Es el único punto
        // donde se conoce con certeza: un pago liquida moratorios, luego interés y al final capital,
        // y ese reparto lo decide `applyPayment`. Deducirlo después, restando saldos en otro
        // servicio, funciona sólo mientras los eventos lleguen en orden.
        BigDecimal capAntes = account.getPrincipalBalance();
        BigDecimal intAntes = account.getAccruedInterestBalance();
        BigDecimal morAntes = account.getPenaltyBalance();
        BigDecimal ivaAntes = account.getIvaBalance();

        mutation.accept(account);
        accountRepository.save(account);

        BigDecimal capDelta = account.getPrincipalBalance().subtract(capAntes);
        BigDecimal intDelta = account.getAccruedInterestBalance().subtract(intAntes);
        BigDecimal ivaDelta = account.getIvaBalance().subtract(ivaAntes);
        // El IVA viaja sumado a los moratorios: contabilidad los abona al mismo auxiliar (1203),
        // así que separarlos aquí obligaría a un cuarto campo que el mayor volvería a fundir.
        BigDecimal morDelta = account.getPenaltyBalance().subtract(morAntes).add(ivaDelta);

        // El importe del hecho se **mide**, no se cree.
        //
        // El `delta` que declara el llamador es una conveniencia y puede no corresponder: el
        // quebranto lo pasaba como ZERO literal porque cuánto se castiga no se sabe hasta después
        // de poner los saldos en cero. Con ese cero, el evento viajaba sin importe y contabilidad lo
        // descartaba —«sólo cambió el estatus»—: el crédito desaparecía de cartera y seguía vivo en
        // el mayor, con la cuenta 1201 sobrando exactamente lo castigado.
        BigDecimal medido = capDelta.add(intDelta).add(morDelta);

        balanceEventRepository.save(BalanceEvent.record(
                accountId, sourceEventId, eventType, medido, account));

        eventPublisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                account.getCreditAccountId(),
                account.getObligorPartyId(),
                account.getPrincipalBalance(),
                account.getAccruedInterestBalance(),
                account.getPenaltyBalance(),
                account.getAvailableCredit(),
                account.getTotalDebt(),
                eventType,
                account.getStatus().name(),
                account.getBalanceVersion(),
                account.getOriginUnitCode(),
                // El hecho ocurrió el día del cargo, no el día en que se reconcilió. Contabilidad
                // deriva el período de aquí, así que es lo que le da historia al mayor.
                effectiveDate != null
                        ? effectiveDate.atTime(23, 0).toInstant(java.time.ZoneOffset.UTC)
                        : null,
                // Y el importe del hecho, que aquí se conoce con certeza: es el mismo que se acaba
                // de guardar en `balance_events`. Que viaje evita que contabilidad lo deduzca
                // restando saldos, que es de donde salían importes de seis cifras en un devengo
                // diario cuando dos eventos del mismo crédito se cruzaban.
                medido, capDelta, intDelta, morDelta));

        log.info("Balance reconciled accountId={} event={} totalDebt={} status={}",
                accountId, eventType, account.getTotalDebt(), account.getStatus());
    }
}
