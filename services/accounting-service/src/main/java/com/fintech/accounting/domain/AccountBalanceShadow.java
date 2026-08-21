package com.fintech.accounting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sombra local de saldos por cuenta. {@code balance-updated} solo trae saldos nuevos (no el monto
 * de la transacción), así que Accounting deriva el monto como el delta contra el saldo previo.
 * También es el lado "operacional" de la conciliación de cartera (GL-04).
 */
@Entity
@Table(name = "account_balance_shadows", schema = "accounting")
public class AccountBalanceShadow {

    @Id
    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", nullable = false)
    private UUID obligorPartyId;

    @Column(name = "principal_balance", nullable = false)
    private BigDecimal principalBalance;

    @Column(name = "interest_balance", nullable = false)
    private BigDecimal interestBalance;

    @Column(name = "penalty_balance", nullable = false)
    private BigDecimal penaltyBalance;

    @Column(name = "total_debt", nullable = false)
    private BigDecimal totalDebt;

    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * La sucursal que colocó el crédito, aprendida del primer evento que la traiga.
     *
     * <p>Se recuerda aquí en vez de exigirla en cada payload: una vez sellada, todas las pólizas
     * siguientes de ese préstamo la llevan aunque un productor la omita. Que la atribución por
     * sucursal dependa de que ningún emisor se salte el campo nunca es una forma cara de perder la
     * contabilidad de una rama entera sin enterarse.
     */
    @Column(name = "org_unit_code", length = 40)
    private String orgUnitCode;

    protected AccountBalanceShadow() {}

    public static AccountBalanceShadow init(UUID creditAccountId, UUID obligorPartyId) {
        AccountBalanceShadow s = new AccountBalanceShadow();
        s.creditAccountId  = creditAccountId;
        s.obligorPartyId   = obligorPartyId;
        s.principalBalance = BigDecimal.ZERO;
        s.interestBalance  = BigDecimal.ZERO;
        s.penaltyBalance   = BigDecimal.ZERO;
        s.totalDebt        = BigDecimal.ZERO;
        s.balanceVersion   = -1L;
        s.updatedAt        = Instant.now();
        return s;
    }

    /**
     * Aplica los nuevos saldos y devuelve el delta contra los anteriores. Devuelve null si la
     * versión es vieja/duplicada (entrega estancada) — no se debe postear nada.
     */
    public BalanceDelta applyAndComputeDelta(BigDecimal newPrincipal, BigDecimal newInterest,
                                             BigDecimal newPenalty, BigDecimal newTotal, long version) {
        if (version <= this.balanceVersion) return null;
        BalanceDelta delta = new BalanceDelta(
                newPrincipal.subtract(principalBalance),
                newInterest.subtract(interestBalance),
                newPenalty.subtract(penaltyBalance),
                newTotal.subtract(totalDebt));
        this.principalBalance = newPrincipal;
        this.interestBalance  = newInterest;
        this.penaltyBalance   = newPenalty;
        this.totalDebt        = newTotal;
        this.balanceVersion   = version;
        this.updatedAt        = Instant.now();
        return delta;
    }

    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public BigDecimal getPrincipalBalance() { return principalBalance; }
    public BigDecimal getInterestBalance()  { return interestBalance; }
    public BigDecimal getPenaltyBalance()   { return penaltyBalance; }
    public BigDecimal getTotalDebt()  { return totalDebt; }
    public long getBalanceVersion()   { return balanceVersion; }

    /**
     * Nunca se ha visto un saldo de esta cuenta.
     *
     * <p>Importa porque el monto de cada asiento sale del delta contra el saldo anterior: si el
     * anterior es cero porque no vimos la historia —y no porque la cuenta estuviera en cero—, el
     * delta es el saldo completo y se le atribuye al hecho que casualmente llegó primero.
     */
    public boolean isUnseen() { return balanceVersion < 0; }

    /**
     * Deja el capital colocado como saldo conocido al activar el crédito.
     *
     * <p>Va en la versión 0 a propósito, por debajo de la que trae cualquier {@code balance-updated}
     * posterior: así el siguiente evento real sigue aplicando y su delta se calcula contra el capital
     * dispuesto, no contra cero. Sin esto, el primer cargo del crédito traería un delta del tamaño
     * del préstamo entero.
     */
    public void seedDisbursement(BigDecimal principal) {
        if (principal == null || principal.signum() <= 0 || !isUnseen()) return;
        this.principalBalance = principal;
        this.totalDebt        = principal;
        this.balanceVersion   = 0L;
        this.updatedAt        = Instant.now();
    }
    public String getOrgUnitCode()    { return orgUnitCode; }

    /** Sella la sucursal la primera vez que se conoce. No la reescribe: mover la cartera de un
     *  ejecutivo a otro no puede cambiar a qué rama se le atribuyó el ingreso de meses pasados. */
    public void learnOrgUnit(String code) {
        if (orgUnitCode == null && code != null && !code.isBlank()) this.orgUnitCode = code;
    }
}
