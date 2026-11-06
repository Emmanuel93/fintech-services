package com.fintech.banking.application.service;

import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.port.out.FundingEventPublisher;
import com.fintech.banking.application.port.out.FundingRepository;
import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingMovement.Type;
import com.fintech.banking.domain.FundingRuleException;
import com.fintech.banking.domain.FundingSource;
import com.fintech.banking.domain.FundingSource.CounterpartyType;
import com.fintech.banking.domain.InternalMovement;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Treasury's funding: the capital paid in and the lines drawn, repaid and paying interest. Each movement is recorded
 * with the balances it left, announced to accounting (which books it: equity, a financial liability at amortised
 * cost, interest expense) and projected for the bank reconciliation when money moves.
 *
 * <p>A movement is dated by its value date, never later than today: the books carry the day the money moved. An
 * instruction recorded twice (same source, type and reference) is the movement already recorded. Before a line's
 * drawn principal changes, and before its interest is paid, its interest is accrued up to that day.
 */
@Service
public class FundingService {

    private static final Logger log = LoggerFactory.getLogger(FundingService.class);

    private final FundingRepository funding;
    private final BankAccountRepository bankAccounts;
    private final FundingEventPublisher events;
    private final InternalMovementProjector projector;

    public FundingService(FundingRepository funding, BankAccountRepository bankAccounts, FundingEventPublisher events,
                          InternalMovementProjector projector) {
        this.funding = funding;
        this.bankAccounts = bankAccounts;
        this.events = events;
        this.projector = projector;
    }

    public record CapitalCommand(String shareholderName, String rfc, String minutesReference, UUID bankAccountId,
                                 BigDecimal subscribedAmount, LocalDate agreedOn) {}

    public record CreditLineCommand(CounterpartyType lenderType, String lenderName, String rfc, String agreementReference,
                                    UUID bankAccountId, BigDecimal authorizedAmount, BigDecimal annualRate,
                                    BigDecimal openingFee, LocalDate startDate, LocalDate maturityDate) {}

    public record MovementCommand(Type type, BigDecimal amount, LocalDate valueDate, String reference) {}

    @Transactional
    public FundingSource registerCapital(CapitalCommand c, String user) {
        requireOwnAccount(c.bankAccountId());
        FundingSource s = FundingSource.capital(c.shareholderName(), c.rfc(), c.minutesReference(), c.bankAccountId(),
                c.subscribedAmount(), c.agreedOn(), user, BusinessClock.current().now());
        log.info("Aportación de capital registrada fuente={} accionista={} acordado={}", s.getId(),
                s.getCounterpartyName(), s.getAuthorizedAmount());
        return funding.save(s);
    }

    @Transactional
    public FundingSource registerCreditLine(CreditLineCommand c, String user) {
        requireOwnAccount(c.bankAccountId());
        FundingSource s = FundingSource.creditLine(c.lenderType(), c.lenderName(), c.rfc(), c.agreementReference(),
                c.bankAccountId(), c.authorizedAmount(), c.annualRate(), c.openingFee(), c.startDate(), c.maturityDate(),
                user, BusinessClock.current().now());
        log.info("Línea de fondeo registrada fuente={} acreditante={} autorizado={} tasa={}", s.getId(),
                s.getCounterpartyName(), s.getAuthorizedAmount(), s.getAnnualRate());
        return funding.save(s);
    }

    /** Records a movement of a source; returns the movements it produced (an accrual before it, then itself). */
    @Transactional
    public List<FundingMovement> record(UUID sourceId, MovementCommand c, String user) {
        if (c.type() == null || c.type() == Type.INTEREST_ACCRUAL) {
            throw new FundingRuleException("El devengo de intereses lo calcula tesorería: se pide con /accruals");
        }
        if (c.reference() == null || c.reference().isBlank()) {
            throw new FundingRuleException("Falta la referencia del movimiento (clave de rastreo o folio)");
        }
        LocalDate today = BusinessClock.current().today();
        if (c.valueDate() == null || c.valueDate().isAfter(today)) {
            throw new FundingRuleException("La fecha valor no puede ser posterior a hoy (" + today + ")");
        }
        FundingSource s = source(sourceId);
        var existing = funding.findMovement(sourceId, c.type(), c.reference().trim());
        if (existing.isPresent()) return List.of(existing.get());

        List<FundingMovement> out = new ArrayList<>();
        switch (c.type()) {
            case CONTRIBUTION -> s.contribute(c.amount(), c.valueDate());
            case DRAWDOWN -> {
                accrue(s, c.valueDate().minusDays(1), user).ifPresent(out::add);
                s.draw(c.amount(), c.valueDate());
            }
            case OPENING_FEE -> {
                if (c.amount() != null && c.amount().compareTo(s.getOpeningFee()) != 0) {
                    throw new FundingRuleException("La comisión de apertura de la línea es " + s.getOpeningFee().toPlainString());
                }
                s.payOpeningFee(c.valueDate());
            }
            case INTEREST_PAYMENT -> {
                accrue(s, c.valueDate(), user).ifPresent(out::add);
                s.payInterest(c.amount(), c.valueDate());
            }
            case PRINCIPAL_REPAYMENT -> {
                accrue(s, c.valueDate().minusDays(1), user).ifPresent(out::add);
                s.repayPrincipal(c.amount(), c.valueDate());
            }
            default -> throw new FundingRuleException("Movimiento no admitido: " + c.type());
        }
        BigDecimal amount = c.type() == Type.OPENING_FEE ? s.getOpeningFee() : c.amount().setScale(2, RoundingMode.HALF_UP);
        funding.save(s);
        FundingMovement m = funding.save(FundingMovement.of(s, c.type(), amount, c.valueDate(), null, null,
                c.reference().trim(), user, BusinessClock.current().now()));
        announce(s, m);
        out.add(m);
        log.info("Fondeo {} fuente={} importe={} fecha={} capital={} intereses={}", c.type(), s.getId(), amount,
                c.valueDate(), s.getPrincipal(), s.getInterestPayable());
        return out;
    }

    /**
     * Accrues every active line's interest through {@code through} (a day not after today). Idempotent: a line already
     * accrued through that day produces nothing. Returns the accrual movements made.
     */
    @Transactional
    public List<FundingMovement> accrueAll(LocalDate through, String user) {
        LocalDate today = BusinessClock.current().today();
        if (through == null || through.isAfter(today)) {
            throw new FundingRuleException("Se devenga hasta un día que ya ocurrió (hoy es " + today + ")");
        }
        List<FundingMovement> out = new ArrayList<>();
        for (FundingSource s : funding.findAll()) {
            if (s.getKind() != FundingSource.Kind.CREDIT_LINE || !"ACTIVE".equals(s.getStatus())) continue;
            accrue(s, through, user).ifPresent(m -> {
                funding.save(s);
                out.add(m);
            });
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<FundingSource> sources() {
        return funding.findAll();
    }

    @Transactional(readOnly = true)
    public List<FundingMovement> movements(UUID sourceId) {
        source(sourceId);
        return funding.movementsOf(sourceId);
    }

    private Optional<FundingMovement> accrue(FundingSource s, LocalDate through, String user) {
        LocalDate from = s.getAccruedThrough() == null ? null : s.getAccruedThrough().plusDays(1);
        BigDecimal interest = s.accrueThrough(through);
        if (interest.signum() <= 0) {
            return Optional.empty();
        }
        FundingMovement m = funding.save(FundingMovement.of(s, Type.INTEREST_ACCRUAL, interest, through, from, through,
                "DEVENGO-" + through, user, BusinessClock.current().now()));
        announce(s, m);
        return Optional.of(m);
    }

    private void announce(FundingSource s, FundingMovement m) {
        events.movementRecorded(s, m);
        if (m.getType().movesCash()) {
            projector.project(InternalMovement.from("FUNDING", "FONDEO:" + s.getId() + ":" + m.getReference(),
                    m.getAmount(), m.getValueDate(), null, m.getType().cashIn() ? "CREDIT" : "DEBIT",
                    "FUNDING:" + m.getId()));
        }
    }

    private FundingSource source(UUID id) {
        return funding.find(id).orElseThrow(() -> new NoSuchElementException("No existe la fuente de fondeo " + id));
    }

    private void requireOwnAccount(UUID bankAccountId) {
        if (bankAccountId == null || bankAccounts.findById(bankAccountId).isEmpty()) {
            throw new FundingRuleException("El fondeo se recibe en una cuenta propia dada de alta en tesorería");
        }
    }
}
