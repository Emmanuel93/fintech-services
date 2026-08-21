package com.fintech.invoicing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Factura (CFDI 4.0). Generada al consumir {@code accounting.invoice-requested}. El receptor es un
 * snapshot del perfil fiscal del party al momento de emitir (trazabilidad). {@code lines} es EAGER
 * (un solo bag) — evita el LazyInitialization al mapear a respuesta fuera de transacción.
 */
@Entity
@Table(name = "invoices", schema = "invoicing")
public class Invoice {

    @Id
    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "invoice_request_id", nullable = false, updatable = false, unique = true)
    private UUID invoiceRequestId;

    @Column(name = "obligor_party_id", nullable = false, updatable = false)
    private UUID obligorPartyId;

    @Column(nullable = false, updatable = false, length = 6)
    private String period;

    @Column(name = "receptor_rfc", nullable = false, updatable = false, length = 13)
    private String receptorRfc;

    @Column(name = "receptor_name", nullable = false, updatable = false, length = 300)
    private String receptorName;

    @Column(name = "receptor_regime", updatable = false, length = 10)
    private String receptorRegime;

    @Column(name = "receptor_zip", updatable = false, length = 5)
    private String receptorZip;

    @Column(name = "cfdi_use", updatable = false, length = 10)
    private String cfdiUse;

    @Column(nullable = false, updatable = false)
    private BigDecimal subtotal;

    @Column(nullable = false, updatable = false)
    private BigDecimal iva;

    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "folio_fiscal")
    private UUID folioFiscal;

    @Column(length = 10)
    private String serie;

    private Long folio;

    @Column(name = "stamped_at")
    private Instant stampedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {}

    public static Invoice draft(UUID invoiceRequestId, UUID obligorPartyId, String period,
                                 String receptorRfc, String receptorName, String receptorRegime,
                                 String receptorZip, String cfdiUse, BigDecimal subtotal,
                                 BigDecimal iva, BigDecimal total, String currency) {
        Invoice i = new Invoice();
        i.invoiceId        = UUID.randomUUID();
        i.invoiceRequestId = invoiceRequestId;
        i.obligorPartyId   = obligorPartyId;
        i.period           = period;
        i.receptorRfc      = receptorRfc;
        i.receptorName     = receptorName;
        i.receptorRegime   = receptorRegime;
        i.receptorZip      = receptorZip;
        i.cfdiUse          = cfdiUse;
        i.subtotal         = subtotal;
        i.iva              = iva;
        i.total            = total;
        i.currency         = currency;
        i.status           = InvoiceStatus.DRAFT;
        i.createdAt        = Instant.now();
        return i;
    }

    public void addLine(String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {
        InvoiceLine line = InvoiceLine.of(concept, creditAccountId, amount, isIva);
        line.assignTo(this);
        lines.add(line);
    }

    /** Timbrado del PAC — asigna folio fiscal (UUID), serie y folio. */
    public void markStamped(UUID folioFiscal, String serie, long folio) {
        this.status      = InvoiceStatus.STAMPED;
        this.folioFiscal = folioFiscal;
        this.serie       = serie;
        this.folio       = folio;
        this.stampedAt   = Instant.now();
    }

    public UUID getInvoiceId()        { return invoiceId; }
    public UUID getInvoiceRequestId() { return invoiceRequestId; }
    public UUID getObligorPartyId()   { return obligorPartyId; }
    public String getPeriod()         { return period; }
    public String getReceptorRfc()    { return receptorRfc; }
    public String getReceptorName()   { return receptorName; }
    public String getReceptorRegime() { return receptorRegime; }
    public String getReceptorZip()    { return receptorZip; }
    public String getCfdiUse()        { return cfdiUse; }
    public BigDecimal getSubtotal()   { return subtotal; }
    public BigDecimal getIva()        { return iva; }
    public BigDecimal getTotal()      { return total; }
    public String getCurrency()       { return currency; }
    public InvoiceStatus getStatus()  { return status; }
    public UUID getFolioFiscal()      { return folioFiscal; }
    public String getSerie()          { return serie; }
    public Long getFolio()            { return folio; }
    public Instant getStampedAt()     { return stampedAt; }
    public Instant getCreatedAt()     { return createdAt; }
    public List<InvoiceLine> getLines() { return List.copyOf(lines); }
}
