package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
    @Column(name = "updated_at", nullable = false)        private Instant updatedAt;

    protected AccountCloseProfile() {}

    public static AccountCloseProfile fromActivation(UUID creditAccountId, UUID obligorPartyId,
                                                      String productType, String productBehavior,
                                                      String orgUnitCode, LocalDate activatedOn,
                                                      String paymentFrequency, Integer termPeriods,
                                                      BigDecimal nominalRate, BigDecimal principalBalance) {
        AccountCloseProfile p = new AccountCloseProfile();
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
        if (balanceVersion <= lastBalanceVersion) {
            return false;
        }
        this.principalBalance   = principal != null ? principal : this.principalBalance;
        this.totalDebt          = totalDebt != null ? totalDebt : this.totalDebt;
        if (accountStatus != null) this.status = accountStatus;
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

    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public UUID getProductId()        { return productId; }
    public String getProductType()    { return productType; }
    public String getProductBehavior(){ return productBehavior; }
    public String getOrgUnitCode()    { return orgUnitCode; }
    public String getStatus()         { return status; }
    public LocalDate getActivatedOn() { return activatedOn; }
    public String getPaymentFrequency() { return paymentFrequency; }
    public PaymentCadence cadence()   { return PaymentCadence.from(paymentFrequency); }
    public Integer getTermPeriods()   { return termPeriods; }
    public BigDecimal getNominalRate(){ return nominalRate; }
    public BigDecimal getPrincipalBalance() { return principalBalance; }
    public BigDecimal getTotalDebt()  { return totalDebt; }
    public int getDaysDelinquent()    { return daysDelinquent; }
    public int getCurrentCycle()      { return currentCycle; }
    public LocalDate getNextCutoffDate() { return nextCutoffDate; }
    public long getLastBalanceVersion()  { return lastBalanceVersion; }
}
