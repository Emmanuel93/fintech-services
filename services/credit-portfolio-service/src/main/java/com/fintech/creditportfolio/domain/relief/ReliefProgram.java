package com.fintech.creditportfolio.domain.relief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Un programa de apoyo por contingencia: se corren N pagos a quienes cumplan un criterio.
 *
 * <p><b>Es lo contrario del convenio que ya existía.</b> {@code CollectionAgreement.RESTRUCTURE} es
 * bilateral y de uno en uno, atado a un caso de cobranza. Un programa de contingencia es masivo, lo
 * otorga la institución, y aplica sobre cuentas que pueden estar <b>al corriente</b>.
 *
 * <p><b>Jurídicamente es una reestructura</b>, y por ahí va. El camino alterno —un tratamiento
 * contable especial— depende de una autorización que puede no estar vigente cuando la contingencia
 * ocurra, que es exactamente cuando hay que actuar rápido.
 *
 * <p>El costo se asume: marca forborne, piso IFRS-9 STAGE_2, reloj de cura reiniciado, y la reserva
 * sube. A cambio el historial ante el buró no se degrada — {@code collections} sólo reporta
 * {@code WRITE_OFF} y {@code QUITA_PARCIAL}.
 */
@Entity
@Table(name = "relief_programs", schema = "credit_portfolio")
public class ReliefProgram {

    @Id
    @Column(name = "relief_program_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "reason", nullable = false, length = 20)
    private String reason;

    @Column(name = "deferred_periods", nullable = false)
    private int deferredPeriods;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    @Column(name = "product_code", length = 20)
    private String productCode;

    @Column(name = "origin_unit_code", length = 40)
    private String originUnitCode;

    @Column(name = "region", length = 60)
    private String region;

    @Column(name = "max_days_delinquent")
    private Integer maxDaysDelinquent;

    /**
     * «Al corriente a esta fecha».
     *
     * <p>Sin fecha de corte, un programa anunciado hoy alcanzaría a quien dejó de pagar al enterarse
     * de que venía. La fecha se fija <b>antes</b> del anuncio y no se mueve.
     */
    @Column(name = "eligibility_cutoff_date", nullable = false)
    private LocalDate eligibilityCutoffDate;

    /** {@code ACCRUES} el crédito sigue devengando durante el apoyo · {@code WAIVED} se condona. */
    @Column(name = "accrual_during_relief", nullable = false, length = 8)
    private String accrualDuringRelief;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "proposed_by", nullable = false, length = 80)
    private String proposedBy;

    @Column(name = "approved_by", length = 80)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReliefProgram() {}

    public static ReliefProgram proponer(String name, String reason, int deferredPeriods,
                                         LocalDate validFrom, LocalDate validTo,
                                         String productCode, String originUnitCode, String region,
                                         Integer maxDaysDelinquent, LocalDate eligibilityCutoffDate,
                                         String accrualDuringRelief, String proposedBy) {
        if (deferredPeriods <= 0) {
            throw new IllegalArgumentException("Un programa que difiere cero pagos no apoya a nadie");
        }
        if (validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("La vigencia termina antes de empezar");
        }
        if (eligibilityCutoffDate.isAfter(validFrom)) {
            // La fecha de corte va ANTES de la vigencia: fijarla después dejaría entrar a quien
            // dejó de pagar ya anunciado el programa.
            throw new IllegalArgumentException(
                    "La fecha de corte de elegibilidad no puede ser posterior al inicio de la vigencia");
        }
        ReliefProgram p = new ReliefProgram();
        p.id                    = UUID.randomUUID();
        p.name                  = name;
        p.reason                = reason;
        p.deferredPeriods       = deferredPeriods;
        p.validFrom             = validFrom;
        p.validTo               = validTo;
        p.productCode           = productCode;
        p.originUnitCode        = originUnitCode;
        p.region                = region;
        p.maxDaysDelinquent     = maxDaysDelinquent;
        p.eligibilityCutoffDate = eligibilityCutoffDate;
        p.accrualDuringRelief   = accrualDuringRelief != null ? accrualDuringRelief : "ACCRUES";
        p.status                = "PROPOSED";
        p.proposedBy            = proposedBy;
        p.createdAt             = Instant.now();
        return p;
    }

    /**
     * Maker-checker: quien lo propone <b>no</b> lo autoriza.
     *
     * <p>No es ceremonia. Un programa de apoyo mueve la fecha de pago de una cartera entera y sube
     * la reserva; que una sola persona pueda hacerlo sin contraparte es el tipo de facultad que una
     * revisión pregunta primero.
     */
    public void autorizar(String approvedBy) {
        if (!"PROPOSED".equals(status)) {
            throw new IllegalStateException("El programa " + id + " está en " + status
                    + ": sólo se autoriza uno PROPOSED");
        }
        if (proposedBy.equalsIgnoreCase(approvedBy)) {
            throw new IllegalStateException(
                    "Quien propone un programa de apoyo no puede autorizarlo (maker-checker)");
        }
        this.status     = "APPROVED";
        this.approvedBy = approvedBy;
        this.approvedAt = Instant.now();
    }

    public void rechazar(String rejectedBy) {
        if (!"PROPOSED".equals(status)) {
            throw new IllegalStateException("Sólo se rechaza un programa PROPOSED");
        }
        this.status     = "REJECTED";
        this.approvedBy = rejectedBy;
        this.approvedAt = Instant.now();
    }

    public void cerrar() { this.status = "CLOSED"; }

    public boolean estaAutorizado() { return "APPROVED".equals(status); }

    /** Durante el apoyo el crédito no devenga: el interés de esos períodos se condona. */
    public boolean condonaDevengo() { return "WAIVED".equals(accrualDuringRelief); }

    public boolean vigenteEl(LocalDate dia) {
        return !dia.isBefore(validFrom) && !dia.isAfter(validTo);
    }

    /**
     * Si esta cuenta entra al padrón.
     *
     * <p>Los filtros nulos no filtran, y se combinan con AND: un programa sin criterios alcanza a
     * toda la cartera, que es una decisión legítima y que alguien tiene que autorizar a propósito.
     */
    public boolean alcanzaA(String producto, String unidad, String regionDeLaCuenta, int dpdAlCorte) {
        if (productCode != null && !productCode.equalsIgnoreCase(producto)) return false;
        if (originUnitCode != null && !originUnitCode.equalsIgnoreCase(unidad)) return false;
        if (region != null && !region.equalsIgnoreCase(regionDeLaCuenta)) return false;
        return maxDaysDelinquent == null || dpdAlCorte <= maxDaysDelinquent;
    }

    public UUID getId()                        { return id; }
    public String getName()                    { return name; }
    public String getReason()                  { return reason; }
    public int getDeferredPeriods()            { return deferredPeriods; }
    public LocalDate getValidFrom()            { return validFrom; }
    public LocalDate getValidTo()              { return validTo; }
    public String getProductCode()             { return productCode; }
    public String getOriginUnitCode()          { return originUnitCode; }
    public String getRegion()                  { return region; }
    public Integer getMaxDaysDelinquent()      { return maxDaysDelinquent; }
    public LocalDate getEligibilityCutoffDate(){ return eligibilityCutoffDate; }
    public String getAccrualDuringRelief()     { return accrualDuringRelief; }
    public String getStatus()                  { return status; }
    public String getProposedBy()              { return proposedBy; }
    public String getApprovedBy()              { return approvedBy; }
    public Instant getApprovedAt()             { return approvedAt; }
}
