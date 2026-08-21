package com.fintech.accounting.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Póliza contable: el encabezado de un hecho económico, con folio consecutivo por (tipo, sucursal,
 * período) y N renglones que suman cargos = abonos.
 *
 * <p><b>Por qué existe.</b> Antes, un hecho se guardaba como pares débito/crédito sueltos en
 * {@code journal_entries} y lo único que los unía era el prefijo del {@code sourceEventId}. Eso
 * impide tres cosas que una contabilidad real necesita: un folio que se pueda seguir, un estatus, y
 * sobre todo un hecho con <b>más de dos renglones</b> — un pago que liquida capital e intereses a la
 * vez no cabe en un par.
 *
 * <p><b>Inmutable una vez posteada.</b> Cancelar no borra ni edita: emite una póliza de reversa que
 * apunta a ésta con {@code reversalRef} y ambas quedan visibles. El cuadre lo garantiza la base con
 * {@code chk_vouchers_balanced}, no una prueba: una póliza descuadrada es el único error de este
 * servicio que no se corrige después sin tocar estados financieros ya publicados.
 */
@Entity
@Table(name = "vouchers", schema = "accounting")
public class Voucher {

    @Id
    @Column(name = "voucher_id", nullable = false, updatable = false)
    private UUID voucherId;

    @Enumerated(EnumType.STRING)
    @Column(name = "voucher_type", nullable = false, updatable = false, length = 8)
    private VoucherType voucherType;

    /** La sucursal que colocó el crédito, sellada al activarlo. Nula = anterior al sellado. */
    @Column(name = "org_unit_code", updatable = false, length = 40)
    private String orgUnitCode;

    @Column(nullable = false, updatable = false, length = 6)
    private String period;

    @Column(nullable = false, updatable = false)
    private long folio;

    /** La fecha del <b>hecho</b>, no la del posteo. De aquí sale el período. */
    @Column(name = "voucher_date", nullable = false, updatable = false)
    private Instant voucherDate;

    @Column(nullable = false, updatable = false, length = 300)
    private String concept;

    @Column(name = "source_event_id", nullable = false, updatable = false, length = 120)
    private String sourceEventId;

    @Column(name = "trigger_event", nullable = false, updatable = false, length = 60)
    private String triggerEvent;

    @Column(name = "credit_account_id", updatable = false)
    private UUID creditAccountId;

    @Column(name = "obligor_party_id", updatable = false)
    private UUID obligorPartyId;

    @Column(name = "total_debit", nullable = false, updatable = false)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, updatable = false)
    private BigDecimal totalCredit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private VoucherStatus status;

    @Column(name = "is_late_posting", nullable = false, updatable = false)
    private boolean latePosting;

    @Column(name = "original_period", updatable = false, length = 6)
    private String originalPeriod;

    @Column(name = "reversal_ref")
    private UUID reversalRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Voucher() {}

    public static Voucher open(VoucherType type, String orgUnitCode, String period, long folio,
                               Instant voucherDate, String concept, String sourceEventId,
                               String triggerEvent, UUID creditAccountId, UUID obligorPartyId,
                               BigDecimal total, String originalPeriod) {
        Voucher v = new Voucher();
        v.voucherId       = UUID.randomUUID();
        v.voucherType     = type;
        v.orgUnitCode     = orgUnitCode;
        v.period          = period;
        v.folio           = folio;
        v.voucherDate     = voucherDate;
        v.concept         = concept;
        v.sourceEventId   = sourceEventId;
        v.triggerEvent    = triggerEvent;
        v.creditAccountId = creditAccountId;
        v.obligorPartyId  = obligorPartyId;
        v.totalDebit      = total;
        v.totalCredit     = total;
        v.status          = VoucherStatus.POSTED;
        // Extemporánea sólo si el período del hecho no es aquel en que se asienta. Que sean iguales
        // es el caso normal y no merece bandera.
        v.latePosting     = originalPeriod != null && !originalPeriod.equals(period);
        v.originalPeriod  = v.latePosting ? originalPeriod : null;
        v.createdAt       = Instant.now();
        return v;
    }

    /** La deja cancelada apuntando a la reversa que la neutraliza. */
    public void cancelledBy(UUID reversalVoucherId) {
        this.status      = VoucherStatus.CANCELLED;
        this.reversalRef = reversalVoucherId;
    }

    public UUID getVoucherId()         { return voucherId; }
    public VoucherType getVoucherType() { return voucherType; }
    public String getOrgUnitCode()     { return orgUnitCode; }
    public String getPeriod()          { return period; }
    public long getFolio()             { return folio; }
    public Instant getVoucherDate()    { return voucherDate; }
    public String getConcept()         { return concept; }
    public String getSourceEventId()   { return sourceEventId; }
    public String getTriggerEvent()    { return triggerEvent; }
    public UUID getCreditAccountId()   { return creditAccountId; }
    public UUID getObligorPartyId()    { return obligorPartyId; }
    public BigDecimal getTotalDebit()  { return totalDebit; }
    public BigDecimal getTotalCredit() { return totalCredit; }
    public VoucherStatus getStatus()   { return status; }
    public boolean isLatePosting()     { return latePosting; }
    public String getOriginalPeriod()  { return originalPeriod; }
    public UUID getReversalRef()       { return reversalRef; }
    public Instant getCreatedAt()      { return createdAt; }
}
