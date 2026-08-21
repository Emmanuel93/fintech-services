package com.fintech.party.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "parties", schema = "party")
public class Party {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID partyId;

    @Column(nullable = false, updatable = false, unique = true)
    private UUID prospectId;

    /** Evaluación de scoring que aprobó a este party. Null hasta que scoring complete. */
    @Column(updatable = false)
    private UUID evaluationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PartyType partyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartyStatus status;

    @Column(updatable = false, length = 100)
    private String firstName;

    @Column(updatable = false, length = 100)
    private String lastName1;

    @Column(updatable = false, length = 100)
    private String lastName2;

    @Column(updatable = false, unique = true, length = 18)
    private String curp;

    @Column(updatable = false, length = 13)
    private String rfc;

    @Column(updatable = false)
    private LocalDate dateOfBirth;

    /** Nivel de riesgo asignado por scoring. Null hasta que scoring complete. */
    @Column(length = 10)
    private String riskLevel;

    /** Score total asignado por scoring. Null hasta que scoring complete. */
    private Integer totalScore;

    // ── Perfil fiscal CFDI 4.0 — requerido para facturar al party como receptor ──
    @Column(name = "tax_name", length = 300)
    private String taxName;          // razón social / nombre fiscal (SAT)

    @Column(name = "tax_regime", length = 10)
    private String taxRegime;        // régimen fiscal SAT (601, 612, 605, 616, ...)

    @Column(name = "tax_zip_code", length = 5)
    private String taxZipCode;       // código postal del domicilio fiscal

    @Column(name = "cfdi_use", length = 10)
    private String cfdiUse;          // uso CFDI por defecto (G03, P01, ...)

    @Column(name = "fiscal_profile_updated_at")
    private Instant fiscalProfileUpdatedAt;

    // ── Ejecutivo de cuenta (backoffice) ─────────────────────────────────────
    // Qué empleado (StaffUser de identity, rol EXECUTIVE) lleva a este cliente.
    // El nombre se desnormaliza al asignar para pintarlo sin ir a identity por fila.
    @Column(name = "assigned_executive_id")
    private UUID assignedExecutiveId;

    @Column(name = "assigned_executive_name", length = 200)
    private String assignedExecutiveName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Party() {}

    /**
     * Crea un Party con estado PROSPECT.
     * Los campos de scoring (evaluationId, riskLevel, totalScore) se establecen
     * cuando scoring-service complete la evaluación.
     */
    public static Party create(UUID partyId, UUID prospectId,
                               PartyType partyType, String firstName, String lastName1,
                               String lastName2, String curp, String rfc,
                               LocalDate dateOfBirth) {
        Party p = new Party();
        p.partyId           = partyId;
        p.prospectId        = prospectId;
        p.evaluationId      = null;
        p.partyType         = partyType;
        p.status            = PartyStatus.PROSPECT;
        p.firstName         = firstName;
        p.lastName1         = lastName1;
        p.lastName2         = lastName2;
        p.curp              = curp;
        p.rfc               = rfc;
        p.dateOfBirth       = dateOfBirth;
        p.riskLevel         = null;
        p.totalScore        = null;
        p.createdAt         = Instant.now();
        return p;
    }

    public void blacklist(String reason) {
        if (this.status == PartyStatus.BLACKLISTED) {
            throw new IllegalStateException("Party " + partyId + " is already BLACKLISTED");
        }
        this.status = PartyStatus.BLACKLISTED;
    }

    /** Captura/actualiza el perfil fiscal necesario para emitir CFDI al party. */
    public void updateFiscalProfile(String taxName, String taxRegime, String taxZipCode, String cfdiUse) {
        this.taxName      = taxName;
        this.taxRegime    = taxRegime;
        this.taxZipCode   = taxZipCode;
        this.cfdiUse      = cfdiUse;
        this.fiscalProfileUpdatedAt = Instant.now();
    }

    public boolean hasFiscalProfile() {
        return taxName != null && taxRegime != null && taxZipCode != null;
    }

    /** Asigna (o reasigna) el ejecutivo de cuenta. El nombre se guarda desnormalizado. */
    public void assignExecutive(UUID executiveId, String executiveName) {
        this.assignedExecutiveId = executiveId;
        this.assignedExecutiveName = executiveName;
    }

    public UUID getPartyId()            { return partyId; }
    public UUID getProspectId()         { return prospectId; }
    public UUID getEvaluationId()       { return evaluationId; }
    public PartyType getPartyType()     { return partyType; }
    public PartyStatus getStatus()      { return status; }
    public String getFirstName()        { return firstName; }
    public String getLastName1()        { return lastName1; }
    public String getLastName2()        { return lastName2; }
    public String getCurp()             { return curp; }
    public String getRfc()              { return rfc; }
    public LocalDate getDateOfBirth()   { return dateOfBirth; }
    public String getRiskLevel()        { return riskLevel; }
    public Integer getTotalScore()      { return totalScore; }
    public String getTaxName()          { return taxName; }
    public String getTaxRegime()        { return taxRegime; }
    public String getTaxZipCode()       { return taxZipCode; }
    public String getCfdiUse()          { return cfdiUse; }
    public Instant getFiscalProfileUpdatedAt() { return fiscalProfileUpdatedAt; }
    public UUID getAssignedExecutiveId()       { return assignedExecutiveId; }
    public String getAssignedExecutiveName()   { return assignedExecutiveName; }
    public Instant getCreatedAt()       { return createdAt; }
}
