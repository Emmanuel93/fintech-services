package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * Where the money the institution lends comes from: its paid-in capital, or a line a lender granted it.
 *
 * <p>A SOFOM ENR may not take deposits from the public (LIC arts. 2 and 103; LGOAAC 87-B). It lends its own capital and
 * what financial institutions or other entities lend it under a private contract, and those are the only two kinds.
 * Each one names its counterparty with its RFC and the document behind it — the shareholders' meeting minutes of a
 * capital contribution, the credit agreement of a line — so the origin of every peso is identified (PLD).
 *
 * <p>A line's interest accrues every day on what is drawn, actual days over 360 at its annual rate. Before the drawn
 * principal changes (a drawdown, a repayment) the interest up to the day before is accrued, so each day carries the
 * principal it really had.
 */
@Entity
@Table(name = "funding_sources", schema = "banking")
public class FundingSource {

    public enum Kind { CAPITAL, CREDIT_LINE }

    /** Who funds: a shareholder (capital or a shareholder loan), a bank, a development bank or another entity. */
    public enum CounterpartyType { SHAREHOLDER, BANK, DEVELOPMENT_BANK, OTHER_ENTITY }

    private static final Set<CounterpartyType> LENDERS =
            Set.of(CounterpartyType.BANK, CounterpartyType.DEVELOPMENT_BANK, CounterpartyType.OTHER_ENTITY,
                    CounterpartyType.SHAREHOLDER);
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(360);

    @Id
    @Column(name = "funding_source_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "kind", nullable = false, length = 12)
    private String kind;

    @Column(name = "counterparty_type", nullable = false, length = 20)
    private String counterpartyType;

    @Column(name = "counterparty_name", nullable = false, length = 200)
    private String counterpartyName;

    @Column(name = "counterparty_rfc", nullable = false, length = 13)
    private String counterpartyRfc;

    @Column(name = "contract_reference", nullable = false, length = 120)
    private String contractReference;

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

    @Column(name = "authorized_amount", nullable = false)
    private BigDecimal authorizedAmount;

    @Column(name = "annual_rate")
    private BigDecimal annualRate;

    @Column(name = "opening_fee", nullable = false)
    private BigDecimal openingFee;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "contributed", nullable = false)
    private BigDecimal contributed;

    @Column(name = "principal", nullable = false)
    private BigDecimal principal;

    @Column(name = "interest_payable", nullable = false)
    private BigDecimal interestPayable;

    @Column(name = "accrued_through")
    private LocalDate accruedThrough;

    @Column(name = "opening_fee_paid", nullable = false)
    private boolean openingFeePaid;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected FundingSource() {}

    /** A capital contribution agreed by the shareholders' meeting: {@code subscribed} is what was agreed to be paid in. */
    public static FundingSource capital(String counterpartyName, String rfc, String minutes, UUID bankAccountId,
                                        BigDecimal subscribed, LocalDate agreedOn, String createdBy, Instant now) {
        FundingSource s = base(Kind.CAPITAL, CounterpartyType.SHAREHOLDER, counterpartyName, rfc, minutes,
                bankAccountId, subscribed, agreedOn, createdBy, now);
        s.openingFee = BigDecimal.ZERO;
        return s;
    }

    /** A funding line: authorized amount, annual rate, opening fee and maturity, as the credit agreement sets them. */
    public static FundingSource creditLine(CounterpartyType lender, String counterpartyName, String rfc, String agreement,
                                           UUID bankAccountId, BigDecimal authorized, BigDecimal annualRate,
                                           BigDecimal openingFee, LocalDate startDate, LocalDate maturityDate,
                                           String createdBy, Instant now) {
        if (lender == null || !LENDERS.contains(lender)) {
            throw new FundingRuleException("Una línea de fondeo se contrata con una institución financiera u otra entidad");
        }
        if (annualRate == null || annualRate.signum() < 0 || annualRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new FundingRuleException("La tasa anual de la línea va como fracción (0.13 es 13 %)");
        }
        if (maturityDate == null || !maturityDate.isAfter(startDate)) {
            throw new FundingRuleException("La línea vence después de contratarse");
        }
        FundingSource s = base(Kind.CREDIT_LINE, lender, counterpartyName, rfc, agreement, bankAccountId, authorized,
                startDate, createdBy, now);
        s.annualRate = annualRate;
        s.openingFee = openingFee == null ? BigDecimal.ZERO : openingFee.setScale(2, RoundingMode.HALF_UP);
        s.maturityDate = maturityDate;
        return s;
    }

    private static FundingSource base(Kind kind, CounterpartyType type, String name, String rfc, String document,
                                      UUID bankAccountId, BigDecimal authorized, LocalDate start, String createdBy,
                                      Instant now) {
        if (name == null || name.isBlank()) throw new FundingRuleException("Falta la contraparte del fondeo");
        if (rfc == null || !rfc.trim().toUpperCase().matches("[A-ZÑ&]{3,4}\\d{6}[A-Z0-9]{3}")) {
            throw new FundingRuleException("El RFC de la contraparte no es válido: el origen de los recursos se identifica");
        }
        if (document == null || document.isBlank()) {
            throw new FundingRuleException(kind == Kind.CAPITAL ? "Falta el acta de asamblea que acuerda la aportación"
                                                                : "Falta el contrato de la línea de fondeo");
        }
        if (bankAccountId == null) throw new FundingRuleException("Falta la cuenta propia que recibe el fondeo");
        if (authorized == null || authorized.signum() <= 0) throw new FundingRuleException("El monto debe ser positivo");
        if (start == null) throw new FundingRuleException("Falta la fecha del acuerdo o del contrato");
        FundingSource s = new FundingSource();
        s.id = UUID.randomUUID();
        s.kind = kind.name();
        s.counterpartyType = type.name();
        s.counterpartyName = name.trim();
        s.counterpartyRfc = rfc.trim().toUpperCase();
        s.contractReference = document.trim();
        s.bankAccountId = bankAccountId;
        s.authorizedAmount = authorized.setScale(2, RoundingMode.HALF_UP);
        s.startDate = start;
        s.status = "ACTIVE";
        s.contributed = BigDecimal.ZERO;
        s.principal = BigDecimal.ZERO;
        s.interestPayable = BigDecimal.ZERO;
        s.createdBy = createdBy;
        s.createdAt = now;
        return s;
    }

    // ── Movements ────────────────────────────────────────────────────────────────────────────────

    /** Paid-in capital received; never more than the meeting agreed. */
    public void contribute(BigDecimal amount, LocalDate on) {
        requireKind(Kind.CAPITAL, "Sólo una aportación de capital recibe aportaciones");
        requireActiveOn(on);
        BigDecimal a = positive(amount);
        if (contributed.add(a).compareTo(authorizedAmount) > 0) {
            throw new FundingRuleException("La aportación excede el capital acordado por la asamblea ("
                    + authorizedAmount.toPlainString() + "): un aumento requiere otro acuerdo");
        }
        contributed = contributed.add(a);
    }

    /** Draws on the line. The interest up to the day before must already be accrued (see {@link #accrueThrough}). */
    public void draw(BigDecimal amount, LocalDate on) {
        requireKind(Kind.CREDIT_LINE, "Sólo una línea de fondeo se dispone");
        requireActiveOn(on);
        if (on.isAfter(maturityDate)) throw new FundingRuleException("La línea venció el " + maturityDate);
        BigDecimal a = positive(amount);
        if (principal.add(a).compareTo(authorizedAmount) > 0) {
            throw new FundingRuleException("La disposición excede la línea autorizada (" + authorizedAmount.toPlainString() + ")");
        }
        if (principal.signum() == 0 && accruedThrough == null) {
            accruedThrough = on.minusDays(1);
        }
        principal = principal.add(a);
    }

    /** The line's opening fee, paid once: a transaction cost of the liability. */
    public BigDecimal payOpeningFee(LocalDate on) {
        requireKind(Kind.CREDIT_LINE, "Sólo una línea de fondeo tiene comisión de apertura");
        requireActiveOn(on);
        if (openingFeePaid) throw new FundingRuleException("La comisión de apertura de la línea ya se pagó");
        if (openingFee.signum() <= 0) throw new FundingRuleException("La línea no tiene comisión de apertura");
        openingFeePaid = true;
        return openingFee;
    }

    /**
     * Accrues the interest from the day after the last accrual through {@code through}, on the principal drawn (actual
     * days over 360). Returns the interest of the window, zero when there is none (nothing drawn, or already accrued).
     */
    public BigDecimal accrueThrough(LocalDate through) {
        if (Kind.valueOf(kind) != Kind.CREDIT_LINE || accruedThrough == null || !through.isAfter(accruedThrough)) {
            return BigDecimal.ZERO;
        }
        long days = ChronoUnit.DAYS.between(accruedThrough, through);
        BigDecimal interest = principal.multiply(annualRate).multiply(BigDecimal.valueOf(days))
                .divide(DAYS_PER_YEAR, 2, RoundingMode.HALF_UP);
        accruedThrough = through;
        interestPayable = interestPayable.add(interest);
        return interest;
    }

    public void payInterest(BigDecimal amount, LocalDate on) {
        requireKind(Kind.CREDIT_LINE, "Sólo una línea de fondeo paga intereses");
        BigDecimal a = positive(amount);
        if (a.compareTo(interestPayable) > 0) {
            throw new FundingRuleException("El pago de intereses excede lo devengado por pagar (" + interestPayable.toPlainString() + ")");
        }
        interestPayable = interestPayable.subtract(a);
    }

    /** Repays principal. A line repaid in full stays open: it can be drawn again until it matures. */
    public void repayPrincipal(BigDecimal amount, LocalDate on) {
        requireKind(Kind.CREDIT_LINE, "Sólo una línea de fondeo amortiza capital");
        BigDecimal a = positive(amount);
        if (a.compareTo(principal) > 0) {
            throw new FundingRuleException("El pago excede el capital dispuesto (" + principal.toPlainString() + ")");
        }
        principal = principal.subtract(a);
    }

    /** The line's life in months, from its start to its maturity: what its opening fee is amortized over. */
    public Integer termMonths() {
        if (maturityDate == null) return null;
        return (int) Math.max(1, ChronoUnit.MONTHS.between(startDate.withDayOfMonth(1), maturityDate.withDayOfMonth(1)));
    }

    private void requireKind(Kind expected, String message) {
        if (Kind.valueOf(kind) != expected) throw new FundingRuleException(message);
    }

    private void requireActiveOn(LocalDate on) {
        if (!"ACTIVE".equals(status)) throw new FundingRuleException("La fuente de fondeo está cerrada");
        if (on == null || on.isBefore(startDate)) {
            throw new FundingRuleException("El movimiento no puede ser anterior al acuerdo o contrato (" + startDate + ")");
        }
    }

    private static BigDecimal positive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new FundingRuleException("El importe debe ser positivo");
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getId()                     { return id; }
    public Kind getKind()                   { return Kind.valueOf(kind); }
    public String getCounterpartyType()     { return counterpartyType; }
    public String getCounterpartyName()     { return counterpartyName; }
    public String getCounterpartyRfc()      { return counterpartyRfc; }
    public String getContractReference()    { return contractReference; }
    public UUID getBankAccountId()          { return bankAccountId; }
    public BigDecimal getAuthorizedAmount() { return authorizedAmount; }
    public BigDecimal getAnnualRate()       { return annualRate; }
    public BigDecimal getOpeningFee()       { return openingFee; }
    public LocalDate getStartDate()         { return startDate; }
    public LocalDate getMaturityDate()      { return maturityDate; }
    public String getStatus()               { return status; }
    public BigDecimal getContributed()      { return contributed; }
    public BigDecimal getPrincipal()        { return principal; }
    public BigDecimal getInterestPayable()  { return interestPayable; }
    public LocalDate getAccruedThrough()    { return accruedThrough; }
    public boolean isOpeningFeePaid()       { return openingFeePaid; }
    public Instant getCreatedAt()           { return createdAt; }
}
