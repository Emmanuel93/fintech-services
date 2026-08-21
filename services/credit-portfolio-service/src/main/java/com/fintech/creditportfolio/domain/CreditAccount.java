package com.fintech.creditportfolio.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root — the live credit account.
 *
 * Invariants:
 * CP-01: productType/obligorPartyId/contractId are immutable after activation.
 * CP-02: totalDebt >= 0.
 * CP-03: availableCredit >= 0 (revolving only).
 * CP-04: SUSPENDED does not process dispositions.
 * CP-05: PERSONAL_LOAN/PAYROLL_LOAN → single disposition only.
 * PL-02: ACTIVE only after first DispositionCompleted.
 */
@Entity
@Table(name = "credit_accounts", schema = "credit_portfolio")
public class CreditAccount {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    /** applicationId from origination — used for event correlation. */
    @Column(name = "contract_id", nullable = false, updatable = false)
    private UUID contractId;

    @Column(name = "contract_number", nullable = false, updatable = false)
    private String contractNumber;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    /** Config version pinned at origination — the account reads its behaviour config from this version. */
    @Column(name = "product_version", updatable = false)
    private Integer productVersion;

    @Column(name = "product_type", nullable = false, updatable = false)
    private String productType;

    /** INSTALLMENT | REVOLVING — governs balance-engine behaviour. */
    @Column(name = "product_behavior", nullable = false, updatable = false)
    private String productBehavior;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    /**
     * La sucursal que colocó el crédito. Se sella al activarlo y <b>no se recalcula</b>.
     *
     * <p>Derivarla del ejecutivo que lleva al cliente hoy haría que reasignar la cartera reescribiera
     * la contabilidad de los meses anteriores: la balanza de marzo daría otro número en agosto. El
     * ingreso se lo ganó quien lo colocó, y eso no cambia porque cambie quién lo atiende.
     */
    @Column(name = "origin_unit_code", length = 40)
    private String originUnitCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditAccountStatus status;

    /** Annual nominal rate (e.g. 24.00 = 24%). */
    @Column(name = "nominal_rate", nullable = false)
    private BigDecimal nominalRate;

    @Column(name = "moratorium_rate", nullable = false)
    private BigDecimal moratoriumRate;

    @Column(name = "opening_fee_rate")
    private BigDecimal openingFeeRate;

    /** Months — null for revolving. */
    @Column(name = "assigned_term")
    private Integer assignedTerm;

    /** Credit limit — null for installment. */
    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    @Column(name = "principal_balance", nullable = false)
    private BigDecimal principalBalance;

    @Column(name = "accrued_interest_balance", nullable = false)
    private BigDecimal accruedInterestBalance;

    @Column(name = "penalty_balance", nullable = false)
    private BigDecimal penaltyBalance;

    /**
     * IVA trasladado y pendiente de cobro.
     *
     * <p>Aparte de {@code penaltyBalance} porque no es mora del cliente ni ingreso de la
     * institución: es dinero del SAT que se cobra y se retiene. Mezclado con la penalización, un
     * crédito al corriente mostraba «Penalización $4,200» que eran impuestos, y no había forma de
     * saber cuánto del adeudo le toca a quién.
     */
    @Column(name = "iva_balance", nullable = false)
    private BigDecimal ivaBalance;

    /**
     * IVA que se le aplicó al colocar este crédito, congelado.
     *
     * <p>Depende de la sucursal —la Región Fronteriza tiene estímulo— y se fija al originar: la tasa
     * vigente ese día rige la vida entera del crédito. Releerla en cada devengo haría que una reforma
     * fiscal reescribiera el plan de pagos que el cliente ya firmó.
     */
    @Column(name = "vat_rate", precision = 6, scale = 4)
    private BigDecimal vatRate;

    /** availableCredit = creditLimit − principalBalance − pendingDispositions (revolving only). */
    @Column(name = "available_credit")
    private BigDecimal availableCredit;

    @Column(name = "amortization_type")
    private String amortizationType;

    @Column(name = "risk_tier")
    private String riskTier;

    @Column(name = "clabe_account")
    private String clabeAccount;

    // Identidad del beneficiario, congelada al originar (snapshot inmutable). STP la exige para
    // firmar la orden de pago; viaja en credit-account-activated hacia disbursement.
    @Column(name = "beneficiary_name")
    private String beneficiaryName;

    @Column(name = "beneficiary_tax_id")
    private String beneficiaryTaxId;

    @Column(name = "days_delinquent", nullable = false)
    private int daysDelinquent;

    /** Monotonically incremented on every balance mutation. Carried in balance-updated events
     * so consuming snapshots can detect staleness without polling. */
    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected CreditAccount() {}

    /** Factory — creates the account in PENDING_ACTIVATION from the origination snapshot. */
    public static CreditAccount fromSnapshot(
            UUID contractId,
            String contractNumber,
            UUID obligorPartyId,
            String productCode,
            Integer productVersion,
            String productType,
            String productBehavior,
            BigDecimal approvedAmount,
            BigDecimal approvedLine,
            Integer assignedTerm,
            BigDecimal nominalRate,
            BigDecimal moratoriumRate,
            String amortizationType,
            BigDecimal openingFeeRate,
            String clabeAccount,
            String riskTier,
            String beneficiaryName,
            String beneficiaryTaxId) {

        Instant now = Instant.now();
        CreditAccount a = new CreditAccount();
        a.creditAccountId        = UUID.randomUUID();
        a.contractId             = contractId;
        a.contractNumber         = contractNumber;
        a.obligorPartyId         = obligorPartyId;
        a.productCode            = productCode;
        a.productVersion         = productVersion;
        a.productType            = productType;
        a.productBehavior        = productBehavior;
        a.assignedTerm           = assignedTerm;
        a.nominalRate            = nominalRate;
        a.moratoriumRate         = moratoriumRate;
        a.amortizationType       = amortizationType;
        a.openingFeeRate         = openingFeeRate != null ? openingFeeRate : BigDecimal.ZERO;
        a.clabeAccount           = clabeAccount;
        a.beneficiaryName        = beneficiaryName;
        a.beneficiaryTaxId       = beneficiaryTaxId;
        a.riskTier               = riskTier;
        a.status                 = CreditAccountStatus.PENDING_ACTIVATION;
        a.principalBalance       = BigDecimal.ZERO;
        a.accruedInterestBalance = BigDecimal.ZERO;
        a.penaltyBalance         = BigDecimal.ZERO;
        a.ivaBalance             = BigDecimal.ZERO;
        a.daysDelinquent         = 0;

        // Revolving: set credit limit + available credit (PL-01: no balance until disposition)
        if ("REVOLVING".equalsIgnoreCase(productBehavior)) {
            a.creditLimit    = approvedLine != null ? approvedLine : BigDecimal.ZERO;
            a.availableCredit = a.creditLimit;
        }

        a.createdAt = now;
        a.updatedAt = now;
        return a;
    }

    /**
     * Called when the first disposition completes — activates the account (PL-02).
     * principalBalance is increased by the disbursed amount.
     */
    public void activate(BigDecimal disbursedAmount) {
        if (status != CreditAccountStatus.PENDING_ACTIVATION) {
            throw new IllegalStateException("Account already activated: " + status);
        }
        this.principalBalance = disbursedAmount;
        if ("REVOLVING".equalsIgnoreCase(productBehavior) && creditLimit != null) {
            this.availableCredit = creditLimit.subtract(disbursedAmount);
        }
        this.status      = CreditAccountStatus.ACTIVE;
        this.activatedAt = Instant.now();
        touch();
    }

    // ── Balance engine (the others calculate, portfolio applies) ─────────────

    private void guardActive() {
        if (status == CreditAccountStatus.WRITTEN_OFF || status == CreditAccountStatus.CLOSED) {
            throw new IllegalStateException("Cannot mutate balance of terminal account: " + status);
        }
    }

    /** OrdinaryInterestAccrued (Charges) → accruedInterestBalance += amount. */
    public void applyInterestAccrual(BigDecimal amount) {
        guardActive();
        this.accruedInterestBalance = accruedInterestBalance.add(amount);
        touch();
    }

    /** IVA trasladado (Charges) → ivaBalance += amount. Ni mora ni ingreso: impuesto por enterar. */
    public void applyIvaCharge(BigDecimal amount) {
        guardActive();
        this.ivaBalance = ivaBalance.add(amount);
        touch();
    }

    /** MoratoriumInterestCharged / *FeeCharged (Charges) → penaltyBalance += amount. */
    public void applyPenaltyCharge(BigDecimal amount) {
        guardActive();
        this.penaltyBalance = penaltyBalance.add(amount);
        touch();
    }

    /**
     * ChargeReversed / ChargeWaived (Charges) → reverse the original charge from the right bucket.
     * INTEREST reverses accruedInterestBalance; everything else reverses penaltyBalance.
     * Balances never go below zero (CP-02).
     */
    public void reverseCharge(String originalChargeType, BigDecimal amount) {
        guardActive();
        if ("ORDINARY_INTEREST".equalsIgnoreCase(originalChargeType)
                || "INTEREST".equalsIgnoreCase(originalChargeType)) {
            this.accruedInterestBalance = nonNegative(accruedInterestBalance.subtract(amount));
        } else {
            this.penaltyBalance = nonNegative(penaltyBalance.subtract(amount));
        }
        touch();
    }

    /**
     * PaymentApplied (Payments) → reduce balances in hierarchy: penalty → interest → principal.
     * For revolving accounts, capital repaid frees available credit (CupoLiberated).
     *
     * @return the amount applied to principal (capital), used to liberate revolving credit
     */
    public BigDecimal applyPayment(BigDecimal amount) {
        guardActive();
        BigDecimal remaining = amount;

        // El IVA se liquida primero: es lo único del adeudo que no es de la institución, y dejarlo
        // al final haría que un pago parcial pagara ingreso propio antes que el impuesto por enterar.
        BigDecimal toIva = remaining.min(ivaBalance);
        ivaBalance = ivaBalance.subtract(toIva);
        remaining = remaining.subtract(toIva);

        BigDecimal toPenalty = remaining.min(penaltyBalance);
        penaltyBalance = penaltyBalance.subtract(toPenalty);
        remaining = remaining.subtract(toPenalty);

        BigDecimal toInterest = remaining.min(accruedInterestBalance);
        accruedInterestBalance = accruedInterestBalance.subtract(toInterest);
        remaining = remaining.subtract(toInterest);

        BigDecimal toPrincipal = remaining.min(principalBalance);
        principalBalance = principalBalance.subtract(toPrincipal);
        remaining = remaining.subtract(toPrincipal);

        // Overpayment beyond debt is left as remaining (caller may return-to-payer); not credited here.
        if (isRevolving() && creditLimit != null && toPrincipal.signum() > 0) {
            this.availableCredit = nonNegative(
                    (availableCredit != null ? availableCredit : BigDecimal.ZERO).add(toPrincipal))
                    .min(creditLimit);
        }
        touch();
        return toPrincipal;
    }

    /**
     * CollectionAgreementExecuted(QUITA_PARCIAL) → reduces debt in the same penalty→interest→
     * principal hierarchy as a payment, but with no cash and no revolving credit liberated
     * (forgiveness is not a repayment — the obligor never paid it back).
     */
    public void applyForgiveness(BigDecimal amount) {
        guardActive();
        BigDecimal remaining = amount;

        // El IVA se liquida primero: es lo único del adeudo que no es de la institución, y dejarlo
        // al final haría que un pago parcial pagara ingreso propio antes que el impuesto por enterar.
        BigDecimal toIva = remaining.min(ivaBalance);
        ivaBalance = ivaBalance.subtract(toIva);
        remaining = remaining.subtract(toIva);

        BigDecimal toPenalty = remaining.min(penaltyBalance);
        penaltyBalance = penaltyBalance.subtract(toPenalty);
        remaining = remaining.subtract(toPenalty);

        BigDecimal toInterest = remaining.min(accruedInterestBalance);
        accruedInterestBalance = accruedInterestBalance.subtract(toInterest);
        remaining = remaining.subtract(toInterest);

        BigDecimal toPrincipal = remaining.min(principalBalance);
        principalBalance = principalBalance.subtract(toPrincipal);

        touch();
    }

    /**
     * CollectionAgreementExecuted(RESTRUCTURE) → updates the account's own rate/term for
     * record-keeping. Deliberately minimal (matches T4 Accounting's GL-08 scoping decision —
     * no accounting entry either): does NOT regenerate the amortization schedule (Installment
     * rows already generated stay as-is) and does NOT propagate to Charges' local rate snapshot
     * (still the rate captured at CreditAccountActivated). Full restructure support — schedule
     * regeneration + downstream rate sync — is a known gap, not implemented here.
     */
    public void applyRestructure(BigDecimal newNominalRate, Integer newTermMonths) {
        guardActive();
        if (newNominalRate != null) this.nominalRate = newNominalRate;
        if (newTermMonths != null) this.assignedTerm = newTermMonths;
        touch();
    }

    /**
     * DispositionCompleted (wallet-initiated draw, SELF_USE or THIRD_PARTY_CREDIT) →
     * principalBalance += amount; revolving accounts also reduce availableCredit.
     * Both disposition types increase debt the same way — only the destination of the
     * money (wallet balance vs external SPEI) differs, decided by the caller.
     */
    public void applyDisposition(BigDecimal amount) {
        guardActive();
        this.principalBalance = principalBalance.add(amount);
        if (isRevolving() && creditLimit != null) {
            this.availableCredit = nonNegative(
                    (availableCredit != null ? availableCredit : BigDecimal.ZERO).subtract(amount));
        }
        touch();
    }

    /** NightlyJob → sets daysDelinquent from the oldest unpaid overdue installment. */
    public void updateDelinquency(int days) {
        this.daysDelinquent = Math.max(0, days);
        touch();
    }

    /** WriteOffExecuted (Collections) → all balances to zero, status WRITTEN_OFF (CP-09 terminal). */
    public void executeWriteOff() {
        if (status == CreditAccountStatus.WRITTEN_OFF) return;   // idempotent
        this.principalBalance       = BigDecimal.ZERO;
        this.accruedInterestBalance = BigDecimal.ZERO;
        this.penaltyBalance         = BigDecimal.ZERO;
        this.ivaBalance             = BigDecimal.ZERO;
        this.status                 = CreditAccountStatus.WRITTEN_OFF;
        touch();
    }

    /**
     * Transitions to SETTLED when fully paid with no pending dispositions (PL-04).
     *
     * <p><b>No aplica a revolventes.</b> En un préstamo a plazo, pagar todo es el final: no queda
     * nada que hacer con la cuenta y SETTLED la cierra. En una línea revolvente es lo contrario —
     * pagar libera el cupo para volver a disponer, que es su razón de existir—. Una línea de
     * distribuidor con saldo cero no está terminada: está disponible, con su límite intacto.
     *
     * <p>Sin esta distinción, la distribuidora que liquidaba su única colocación perdía la línea:
     * `findActiveDistributorLine` filtra por ACTIVE, así que la app escondía Colocaciones y
     * Beneficiarios y la mandaba al home B2C con "todavía no tienes un crédito". El producto
     * castigaba a quien paga puntual, que es exactamente el incentivo contrario.
     *
     * <p>El saldo cero de una revolvente ya está representado: `availableCredit == creditLimit`.
     * Cerrarla de verdad es otra cosa —cancelarla— y para eso está CLOSED, que es una decisión,
     * no una consecuencia de haber pagado bien.
     */
    public void settleIfClear() {
        if (isRevolving()) {
            return;
        }
        if (status == CreditAccountStatus.ACTIVE && getTotalDebt().signum() == 0) {
            this.status = CreditAccountStatus.SETTLED;
            touch();
        }
    }

    public boolean isRevolving() {
        return "REVOLVING".equalsIgnoreCase(productBehavior);
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private void touch() { this.updatedAt = Instant.now(); this.balanceVersion++; }

    public UUID getCreditAccountId()          { return creditAccountId; }
    public UUID getContractId()               { return contractId; }
    public String getContractNumber()         { return contractNumber; }
    public String getProductCode()            { return productCode; }
    public Integer getProductVersion()        { return productVersion; }
    public String getProductType()            { return productType; }
    public String getProductBehavior()        { return productBehavior; }
    /** Sella la sucursal la primera vez. No la sobrescribe: el origen de un crédito ocurre una vez. */
    public void sealOriginUnit(String code) {
        if (this.originUnitCode == null && code != null && !code.isBlank()) this.originUnitCode = code;
    }

    public String getOriginUnitCode() { return originUnitCode; }

    public UUID getObligorPartyId()           { return obligorPartyId; }
    public CreditAccountStatus getStatus()    { return status; }
    public BigDecimal getNominalRate()        { return nominalRate; }
    public BigDecimal getMoratoriumRate()     { return moratoriumRate; }
    public BigDecimal getOpeningFeeRate()     { return openingFeeRate; }
    public Integer getAssignedTerm()          { return assignedTerm; }
    public BigDecimal getCreditLimit()        { return creditLimit; }
    public BigDecimal getPrincipalBalance()   { return principalBalance; }
    public BigDecimal getAccruedInterestBalance() { return accruedInterestBalance; }
    public BigDecimal getPenaltyBalance()     { return penaltyBalance; }
    public BigDecimal getIvaBalance()         { return ivaBalance; }
    public BigDecimal getVatRate()            { return vatRate; }
    public void sealVatRate(BigDecimal r)     { if (this.vatRate == null) this.vatRate = r; }
    public BigDecimal getAvailableCredit()    { return availableCredit; }
    public String getAmortizationType()       { return amortizationType; }
    public String getRiskTier()               { return riskTier; }
    public String getClabeAccount()           { return clabeAccount; }
    public String getBeneficiaryName()        { return beneficiaryName; }
    public String getBeneficiaryTaxId()       { return beneficiaryTaxId; }
    public int getDaysDelinquent()            { return daysDelinquent; }
    public Instant getCreatedAt()             { return createdAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
    public Instant getActivatedAt()           { return activatedAt; }

    /** El adeudo íntegro: capital, interés devengado, mora y el IVA que se le trasladó. */
    public BigDecimal getTotalDebt() {
        return principalBalance.add(accruedInterestBalance).add(penaltyBalance).add(ivaBalance);
    }

    public long getBalanceVersion() { return balanceVersion; }
}
