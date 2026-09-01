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
 * Un movimiento del estado de cuenta, <b>tal como lo reporta el banco</b>.
 *
 * <p>Es dato externo: se guarda crudo y no se corrige. Si el banco manda algo raro, lo raro es parte
 * del expediente — «arreglarlo» al ingerirlo destruye la única evidencia de que llegó así.
 */
@Entity
@Table(name = "bank_statement_lines", schema = "banking")
public class BankStatementLine {

    @Id
    @Column(name = "line_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "bank_account_id", nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "value_date")
    private LocalDate valueDate;

    /** {@code CREDIT} abono · {@code DEBIT} cargo. */
    @Column(name = "direction", nullable = false, length = 6)
    private String direction;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    /** Lo que permite el cruce determinista: la clave de rastreo SPEI. */
    @Column(name = "tracking_key", length = 60)
    private String trackingKey;

    @Column(name = "reference", length = 120)
    private String reference;

    @Column(name = "counterparty", length = 160)
    private String counterparty;

    @Column(name = "counterparty_account", length = 40)
    private String counterpartyAccount;

    /**
     * Idempotencia de la ingesta.
     *
     * <p>Los bancos reenvían: el mismo archivo llega dos veces, o el poller repite el día. Sin esta
     * clave, cada reenvío duplicaría los movimientos y la conciliación cuadraría contra el doble.
     */
    @Column(name = "external_id", nullable = false, updatable = false, length = 120)
    private String externalId;

    @Column(name = "ingested_at", nullable = false, updatable = false)
    private OffsetDateTime ingestedAt;

    @Column(name = "match_status", nullable = false, length = 12)
    private String matchStatus;

    protected BankStatementLine() {}

    public static BankStatementLine de(UUID bankAccountId, LocalDate businessDate, String direction,
                                       BigDecimal amount, String externalId, String trackingKey,
                                       String reference, String counterparty,
                                       String counterpartyAccount) {
        if (amount == null || amount.signum() <= 0) {
            // El signo lo lleva `direction`, no el importe: un importe negativo con dirección
            // CREDIT es ambiguo y la ambigüedad en conciliación se paga cara.
            throw new IllegalArgumentException("El importe de un movimiento bancario es positivo");
        }
        BankStatementLine l = new BankStatementLine();
        l.id                  = UUID.randomUUID();
        l.bankAccountId       = bankAccountId;
        l.businessDate        = businessDate;
        l.direction           = direction;
        l.amount              = amount;
        l.externalId          = externalId;
        l.trackingKey         = trackingKey;
        l.reference           = reference;
        l.counterparty        = counterparty;
        l.counterpartyAccount = counterpartyAccount;
        l.ingestedAt          = OffsetDateTime.now();
        l.matchStatus         = "UNMATCHED";
        return l;
    }

    public void marcarCruzado()   { this.matchStatus = "MATCHED"; }
    public void marcarEnPuente()  { this.matchStatus = "SUSPENSE"; }
    public void ignorar()         { this.matchStatus = "IGNORED"; }

    public boolean estaSinCruzar() { return "UNMATCHED".equals(matchStatus); }
    public boolean esAbono()       { return "CREDIT".equals(direction); }

    public UUID getId()                   { return id; }
    public UUID getBankAccountId()        { return bankAccountId; }
    public LocalDate getBusinessDate()    { return businessDate; }
    public String getDirection()          { return direction; }
    public BigDecimal getAmount()         { return amount; }
    public String getTrackingKey()        { return trackingKey; }
    public String getReference()          { return reference; }
    public String getCounterparty()       { return counterparty; }
    public String getCounterpartyAccount(){ return counterpartyAccount; }
    public String getExternalId()         { return externalId; }
    public String getMatchStatus()        { return matchStatus; }
}
