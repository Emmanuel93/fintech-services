package com.fintech.accounting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Asiento contable inmutable de partida doble (un débito, un abono, GL-01 balanceada por
 * construcción). A nivel préstamo: {@code creditAccountId}/{@code obligorPartyId} pueblan el
 * auxiliar; el mayor es la agregación por (cuenta, período).
 */
@Entity
@Table(name = "journal_entries", schema = "accounting")
public class JournalEntry {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    @Column(name = "source_event_id", nullable = false, updatable = false, length = 120)
    private String sourceEventId;

    @Column(name = "trigger_event", nullable = false, updatable = false, length = 60)
    private String triggerEvent;

    @Column(name = "credit_account_id", updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", updatable = false)
    private UUID obligorPartyId;

    @Column(name = "debit_account", nullable = false, updatable = false, length = 10)
    private String debitAccount;

    @Column(name = "credit_account", nullable = false, updatable = false, length = 10)
    private String creditAccount;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(length = 200)
    private String description;

    @Column(name = "posting_date", nullable = false, updatable = false)
    private Instant postingDate;

    @Column(nullable = false, updatable = false, length = 6)
    private String period;

    @Column(name = "reversal_ref")
    private UUID reversalRef;

    /** La póliza que agrupa este asiento con los demás del mismo hecho económico. */
    @Column(name = "voucher_id")
    private UUID voucherId;

    /** Denormalizado desde la póliza: la balanza por sucursal agrega asientos, no encabezados. */
    @Column(name = "org_unit_code", length = 40)
    private String orgUnitCode;

    /** Orden dentro de la póliza. Sin él, dos asientos del mismo hecho salen en orden arbitrario. */
    @Column(name = "line_no")
    private Short lineNo;

    protected JournalEntry() {}

    /**
     * Asienta un par dentro de una póliza.
     *
     * <p><b>El período y la fecha vienen del hecho, no del reloj.</b> Antes salían de
     * {@code YearMonth.now()} en el momento de postear: el devengo del 31 procesado a las 00:03 del
     * día 1 caía en el mes siguiente. Es un error silencioso — nadie lo ve hasta que la balanza de
     * marzo no cuadra con el devengo de marzo, y para entonces el período está cerrado.
     */
    public static JournalEntry post(String sourceEventId, String triggerEvent, UUID creditAccountId,
                                     UUID obligorPartyId, String debitAccount, String creditAccount,
                                     BigDecimal amount, String currency, String description,
                                     UUID voucherId, String orgUnitCode, String period,
                                     Instant postingDate, short lineNo) {
        JournalEntry e = new JournalEntry();
        e.entryId         = UUID.randomUUID();
        e.sourceEventId   = sourceEventId;
        e.triggerEvent    = triggerEvent;
        e.creditAccountId = creditAccountId;
        e.obligorPartyId  = obligorPartyId;
        e.debitAccount    = debitAccount;
        e.creditAccount   = creditAccount;
        e.amount          = amount;
        e.currency        = currency;
        e.description     = description;
        e.postingDate     = postingDate;
        e.period          = period;
        e.voucherId       = voucherId;
        e.orgUnitCode     = orgUnitCode;
        e.lineNo          = lineNo;
        return e;
    }

    public UUID getEntryId()          { return entryId; }
    public String getSourceEventId()  { return sourceEventId; }
    public String getTriggerEvent()   { return triggerEvent; }
    public UUID getCreditAccountId()  { return creditAccountId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public String getDebitAccount()   { return debitAccount; }
    public String getCreditAccount()  { return creditAccount; }
    public BigDecimal getAmount()     { return amount; }
    public String getCurrency()       { return currency; }
    public String getDescription()    { return description; }
    public Instant getPostingDate()   { return postingDate; }
    public String getPeriod()         { return period; }
    public UUID getReversalRef()      { return reversalRef; }
    public UUID getVoucherId()        { return voucherId; }
    public String getOrgUnitCode()    { return orgUnitCode; }
    public Short getLineNo()          { return lineNo; }
}
