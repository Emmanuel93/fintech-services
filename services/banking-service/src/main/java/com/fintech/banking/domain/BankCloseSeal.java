package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * El sello bancario del día o del mes: la cifra de control que el cierre consume.
 *
 * <p>La ecuación que tiene que cuadrar:
 *
 * <pre>
 * saldo de la cuenta contable  +  partidas en conciliación  ==  saldo del estado de cuenta
 * </pre>
 *
 * <p><b>Si no cuadra, no se sella</b> y se levanta un hallazgo {@code LEDGER_VS_BANK}. Sellar «con
 * observaciones» convierte el sello en un trámite: su único valor es que un sello emitido signifique
 * que ese día cuadró.
 */
@Entity
@Table(name = "bank_close_seals", schema = "banking")
public class BankCloseSeal {

    @Id
    @Column(name = "seal_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "period_type", nullable = false, updatable = false, length = 8)
    private String periodType;

    @Column(name = "ledger_balance", nullable = false)
    private BigDecimal ledgerBalance;

    @Column(name = "bank_balance", nullable = false)
    private BigDecimal bankBalance;

    @Column(name = "suspense_total", nullable = false)
    private BigDecimal suspenseTotal;

    @Column(name = "difference", nullable = false)
    private BigDecimal difference;

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "matched_count", nullable = false)
    private int matchedCount;

    @Column(name = "sealed_at", nullable = false, updatable = false)
    private OffsetDateTime sealedAt;

    protected BankCloseSeal() {}

    public static BankCloseSeal calcular(UUID bankAccountId, LocalDate businessDate,
                                         String periodType, BigDecimal saldoContable,
                                         BigDecimal saldoDelBanco, BigDecimal partidas,
                                         int lineas, int cruzadas) {
        BankCloseSeal s = new BankCloseSeal();
        s.id            = UUID.randomUUID();
        s.bankAccountId = bankAccountId;
        s.businessDate  = businessDate;
        s.periodType    = periodType;
        s.ledgerBalance = saldoContable;
        s.bankBalance   = saldoDelBanco;
        s.suspenseTotal = partidas;
        // saldo contable + partidas − saldo del banco. Cero = cuadra.
        s.difference    = saldoContable.add(partidas).subtract(saldoDelBanco);
        s.lineCount     = lineas;
        s.matchedCount  = cruzadas;
        s.sealedAt      = OffsetDateTime.now();
        return s;
    }

    /**
     * Cuadra.
     *
     * <p>Se compara con {@code signum()} y no con {@code equals}: {@code 0} y {@code 0.00} son el
     * mismo número y escalas distintas, y {@code BigDecimal.equals} diría que no lo son. Es el mismo
     * error que hacía que ningún sello del cierre se leyera íntegro al releerlo de la base.
     */
    public boolean cuadra() { return difference.signum() == 0; }

    public UUID getId()                 { return id; }
    public UUID getBankAccountId()      { return bankAccountId; }
    public LocalDate getBusinessDate()  { return businessDate; }
    public String getPeriodType()       { return periodType; }
    public BigDecimal getLedgerBalance(){ return ledgerBalance; }
    public BigDecimal getBankBalance()  { return bankBalance; }
    public BigDecimal getSuspenseTotal(){ return suspenseTotal; }
    public BigDecimal getDifference()   { return difference; }
    public int getLineCount()           { return lineCount; }
    public int getMatchedCount()        { return matchedCount; }
}
