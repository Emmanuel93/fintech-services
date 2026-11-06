package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fintech.shared.time.BusinessClock;
import java.util.Set;

/**
 * Lo que el cierre sabe de una cuenta, aprendido <b>por evento</b>.
 *
 * <p>Es el desacople de cartera: el cierre nunca la consulta en línea. La proyección es eventual
 * por diseño y el sello lo declara — se graba con qué versión de saldo se cerró, y lo que llegue
 * después es extemporáneo, no un error.
 */
@Entity
@Table(name = "account_close_profiles", schema = "closing")
public class AccountCloseProfile {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false) private UUID obligorPartyId;
    @Column(name = "product_id")                          private UUID productId;
    @Column(name = "product_type", nullable = false, length = 40)     private String productType;
    @Column(name = "product_behavior", nullable = false, length = 20) private String productBehavior;
    @Column(name = "org_unit_code", length = 40)          private String orgUnitCode;
    @Column(name = "status", nullable = false, length = 20) private String status;

    @Column(name = "activated_on")                        private LocalDate activatedOn;
    @Column(name = "payment_frequency", length = 20)      private String paymentFrequency;
    @Column(name = "term_periods")                        private Integer termPeriods;
    @Column(name = "nominal_rate", precision = 12, scale = 8) private BigDecimal nominalRate;

    @Column(name = "principal_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalBalance = BigDecimal.ZERO;
    @Column(name = "total_debt", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalDebt = BigDecimal.ZERO;
    @Column(name = "days_delinquent", nullable = false)   private int daysDelinquent;

    @Column(name = "current_cycle", nullable = false)     private int currentCycle;
    @Column(name = "next_cutoff_date")                    private LocalDate nextCutoffDate;
    @Column(name = "next_close_date")                     private LocalDate nextCloseDate;

    @Column(name = "last_balance_version", nullable = false) private long lastBalanceVersion = -1L;
    /**
     * Desde qué día de negocio la cuenta está liquidada, castigada o cerrada. El corte de D filtra por
     * esta fecha y no por el estado de hoy: una cuenta que se liquidó el 11 a las 00:07 sí corta el 10.
     */
    @Column(name = "terminal_since")                      private LocalDate terminalSince;
    @Column(name = "updated_at", nullable = false)        private Instant updatedAt;

    /**
     * The billing cycle of the product the account was opened with, pinned at activation. Null on
     * accounts opened before the product declared one: those keep deriving from the policy table.
     */
    @Column(name = "billing_cutoff_rule", length = 30)    private String billingCutoffRule;
    @Column(name = "billing_cutoff_day")                  private Short billingCutoffDay;
    @Column(name = "billing_due_offset_days")             private Short billingDueOffsetDays;
    @Column(name = "billing_grace_days")                  private Short billingGraceDays;
    @Column(name = "billing_min_payment_rate", precision = 6, scale = 4) private BigDecimal billingMinPaymentRate;
    @Column(name = "billing_shift", length = 10)          private String billingShift;
    /** {@code SEMI_MONTHLY}: the two cutoff days ("6,21") and the day each one is paid by ("18,3"). */
    @Column(name = "billing_cutoff_days", length = 16)    private String billingCutoffDays;
    @Column(name = "billing_due_days", length = 16)       private String billingDueDays;

    /** Los estados con los que una cuenta deja de cortar. Los mismos que filtra la materialización del corte. */
    /** A credit cancelled because its disbursement failed (D63) is terminal too: it has no cycle left to cut. */
    public static final Set<String> TERMINAL = Set.of("SETTLED", "WRITTEN_OFF", "CLOSED", "CANCELLED");

    protected AccountCloseProfile() {}

    public static AccountCloseProfile fromActivation(UUID creditAccountId, UUID obligorPartyId,
                                                      String productType, String productBehavior,
                                                      String orgUnitCode, LocalDate activatedOn,
                                                      String paymentFrequency, Integer termPeriods,
                                                      BigDecimal nominalRate, BigDecimal principalBalance) {
        return fromActivation(creditAccountId, obligorPartyId, productType, productBehavior, orgUnitCode,
                activatedOn, paymentFrequency, termPeriods, nominalRate, principalBalance, null);
    }

    /** @param billingCycle the product's cycle pinned to this account; null keeps the policy fallback */
    public static AccountCloseProfile fromActivation(UUID creditAccountId, UUID obligorPartyId,
                                                      String productType, String productBehavior,
                                                      String orgUnitCode, LocalDate activatedOn,
                                                      String paymentFrequency, Integer termPeriods,
                                                      BigDecimal nominalRate, BigDecimal principalBalance,
                                                      BillingCycle billingCycle) {
        AccountCloseProfile p = new AccountCloseProfile();
        if (billingCycle != null) {
            p.billingCutoffRule     = billingCycle.cutoffRule().name();
            p.billingCutoffDay      = billingCycle.cutoffDay() == null ? null : billingCycle.cutoffDay().shortValue();
            p.billingDueOffsetDays  = (short) billingCycle.paymentDueOffsetDays();
            p.billingGraceDays      = (short) billingCycle.graceDays();
            p.billingMinPaymentRate = billingCycle.minimumPaymentRate();
            p.billingShift          = billingCycle.shift().name();
            p.billingCutoffDays     = csv(billingCycle.cutoffDays());
            p.billingDueDays        = csv(billingCycle.paymentDueDays());
        }
        p.creditAccountId  = creditAccountId;
        p.obligorPartyId   = obligorPartyId;
        p.productType      = productType;
        p.productBehavior  = productBehavior == null ? "INSTALLMENT" : productBehavior;
        p.orgUnitCode      = orgUnitCode;
        p.status           = "ACTIVE";
        p.activatedOn      = activatedOn;
        p.paymentFrequency = paymentFrequency;
        p.termPeriods      = termPeriods;
        p.nominalRate      = nominalRate;
        p.principalBalance = principalBalance == null ? BigDecimal.ZERO : principalBalance;
        p.totalDebt        = p.principalBalance;
        p.updatedAt        = Instant.now();
        return p;
    }

    /**
     * Aplica un saldo nuevo.
     *
     * <p><b>La versión sólo avanza.</b> Un evento fuera de orden no retrocede el saldo recordado:
     * llegaría después uno más nuevo y dejaría la proyección oscilando. Se ignora y ya.
     *
     * @return si el evento se aplicó
     */
    public boolean applyBalance(BigDecimal principal, BigDecimal totalDebt, String accountStatus,
                                 long balanceVersion) {
        return applyBalance(principal, totalDebt, accountStatus, balanceVersion, null);
    }

    /**
     * @param valueDate el día de negocio del hecho; {@code null} = hoy. Si el hecho deja la cuenta en
     *                  un estado terminal, ésa es la fecha desde la que ya no corta.
     */
    public boolean applyBalance(BigDecimal principal, BigDecimal totalDebt, String accountStatus,
                                 long balanceVersion, LocalDate valueDate) {
        if (balanceVersion <= lastBalanceVersion) {
            return false;
        }
        this.principalBalance   = principal != null ? principal : this.principalBalance;
        this.totalDebt          = totalDebt != null ? totalDebt : this.totalDebt;
        if (accountStatus != null) {
            boolean eraTerminal = TERMINAL.contains(this.status);
            boolean esTerminal  = TERMINAL.contains(accountStatus);
            if (esTerminal && !eraTerminal) {
                this.terminalSince = valueDate != null ? valueDate : com.fintech.shared.time.BusinessClock.current().today();
            } else if (!esTerminal) {
                this.terminalSince = null;
            }
            this.status = accountStatus;
        }
        this.lastBalanceVersion = balanceVersion;
        this.updatedAt          = Instant.now();
        return true;
    }

    public void applyDelinquency(int days) {
        this.daysDelinquent = Math.max(0, days);
        this.updatedAt      = Instant.now();
    }

    public void scheduleNextCutoff(int cycle, LocalDate cutoffDate) {
        this.currentCycle   = cycle;
        this.nextCutoffDate = cutoffDate;
        this.updatedAt      = Instant.now();
    }

    public void learnOrgUnit(String orgUnitCode) {
        if (this.orgUnitCode == null && orgUnitCode != null) this.orgUnitCode = orgUnitCode;
    }

    /** Terminal: no devenga, no corta, no entra a ninguna corrida. */
    public boolean isTerminal() {
        return "SETTLED".equals(status) || "WRITTEN_OFF".equals(status) || "CLOSED".equals(status);
    }

    public boolean isRevolving() { return "REVOLVING".equalsIgnoreCase(productBehavior); }

    /** The cycle the account was opened with, when the product declared one. */
    public Optional<BillingCycle> billingCycle() {
        if (billingCutoffRule == null) return Optional.empty();
        return BillingCycle.parse(billingCutoffRule,
                billingCutoffDay == null ? null : billingCutoffDay.intValue(),
                billingDueOffsetDays == null ? null : billingDueOffsetDays.intValue(),
                billingGraceDays == null ? null : billingGraceDays.intValue(),
                billingMinPaymentRate, billingShift,
                ClosePolicy.days(billingCutoffDays), ClosePolicy.days(billingDueDays));
    }

    /** Two days as "6,21"; null without them. */
    private static String csv(List<Integer> days) {
        return days == null ? null : days.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public UUID getProductId()        { return productId; }
    public String getProductType()    { return productType; }
    public String getProductBehavior(){ return productBehavior; }
    public String getOrgUnitCode()    { return orgUnitCode; }
    public String getStatus()         { return status; }
    public LocalDate getActivatedOn() { return activatedOn; }
    public String getPaymentFrequency() { return paymentFrequency; }
    /**
     * La periodicidad con la que esta cuenta corta, <b>tal como la configuró el producto</b>.
     *
     * <p>Cae a mensual cuando el producto no la declaró —es lo que hace cartera al amortizar, así que
     * las dos coinciden— pero {@link #hasDeclaredCadence()} permite al llamador <b>notarlo</b> en vez
     * de que un silencio se vea igual que una decisión.
     */
    public PaymentCadence cadence() {
        return PaymentCadence.parse(paymentFrequency).orElse(PaymentCadence.MONTHLY);
    }

    /** Si el producto declaró su periodicidad. Falso significa que {@link #cadence()} es un default. */
    public boolean hasDeclaredCadence() {
        return PaymentCadence.parse(paymentFrequency).isPresent();
    }
    public Integer getTermPeriods()   { return termPeriods; }
    public BigDecimal getNominalRate(){ return nominalRate; }
    public BigDecimal getPrincipalBalance() { return principalBalance; }
    public BigDecimal getTotalDebt()  { return totalDebt; }
    public int getDaysDelinquent()    { return daysDelinquent; }
    public int getCurrentCycle()      { return currentCycle; }
    public LocalDate getNextCutoffDate() { return nextCutoffDate; }
    public long getLastBalanceVersion()  { return lastBalanceVersion; }
    public LocalDate getTerminalSince() { return terminalSince; }
}
