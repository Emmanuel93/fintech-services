package com.fintech.accounting.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Ingreso devengado pendiente de facturar (interés, comisión, IVA). El BillingRunJob consolida los
 * PENDING de un party/período en un solo CFDI y los marca BILLED.
 */
@Entity
@Table(name = "invoiceable_items", schema = "accounting")
public class InvoiceableItem {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "source_event_id", nullable = false, updatable = false, unique = true, length = 120)
    private String sourceEventId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(name = "credit_account_id", nullable = false, updatable = false)
    private UUID creditAccountId;

    @Column(nullable = false, updatable = false, length = 40)
    private String concept;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "is_iva", nullable = false, updatable = false)
    private boolean isIva;

    @Column(nullable = false, updatable = false, length = 6)
    private String period;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceableItemStatus status;

    @Column(name = "invoice_ref")
    private UUID invoiceRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InvoiceableItem() {}

    public static InvoiceableItem accrue(String sourceEventId, UUID obligorPartyId, UUID creditAccountId,
                                          String concept, BigDecimal amount, boolean isIva, String period) {
        InvoiceableItem i = new InvoiceableItem();
        i.itemId          = UUID.randomUUID();
        i.sourceEventId   = sourceEventId;
        i.obligorPartyId  = obligorPartyId;
        i.creditAccountId = creditAccountId;
        i.concept         = concept;
        i.amount          = amount;
        i.isIva           = isIva;
        i.period          = period;
        i.status          = InvoiceableItemStatus.PENDING;
        i.createdAt       = Instant.now();
        return i;
    }

    public void markBilled(UUID invoiceRef) {
        this.status     = InvoiceableItemStatus.BILLED;
        this.invoiceRef = invoiceRef;
    }

    public UUID getItemId()          { return itemId; }
    public String getSourceEventId() { return sourceEventId; }
    public UUID getObligorPartyId()  { return obligorPartyId; }
    public UUID getCreditAccountId() { return creditAccountId; }
    public String getConcept()       { return concept; }
    public BigDecimal getAmount()    { return amount; }
    public boolean isIva()           { return isIva; }
    public String getPeriod()        { return period; }
    public InvoiceableItemStatus getStatus() { return status; }
    public UUID getInvoiceRef()      { return invoiceRef; }
    public Instant getCreatedAt()    { return createdAt; }
}
