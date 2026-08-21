package com.fintech.invoicing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_lines", schema = "invoicing")
public class InvoiceLine {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(nullable = false, updatable = false, length = 40)
    private String concept;

    @Column(name = "credit_account_id", updatable = false)
    private UUID creditAccountId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "is_iva", nullable = false, updatable = false)
    private boolean isIva;

    protected InvoiceLine() {}

    static InvoiceLine of(String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {
        InvoiceLine l = new InvoiceLine();
        l.id              = UUID.randomUUID();
        l.concept         = concept;
        l.creditAccountId = creditAccountId;
        l.amount          = amount;
        l.isIva           = isIva;
        return l;
    }

    void assignTo(Invoice invoice) { this.invoice = invoice; }

    public UUID getId()              { return id; }
    public String getConcept()       { return concept; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public BigDecimal getAmount()    { return amount; }
    public boolean isIva()           { return isIva; }
}
