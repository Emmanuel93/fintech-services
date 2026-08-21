package com.fintech.invoicing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Proyección local del perfil fiscal del party (receptor del CFDI). */
@Entity
@Table(name = "fiscal_profiles", schema = "invoicing")
public class FiscalProfile {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    /**
     * El id con el que cartera, contabilidad y facturación conocen a este cliente.
     *
     * <p>El crédito nace de una solicitud y arrastra el id del <b>prospecto</b> en su
     * {@code obligorPartyId}, no el del party que se creó después. Buscar el perfil sólo por
     * {@code partyId} no encontraba nada y todo se timbraba a «público en general»: sin error, con
     * folio, y sin más síntoma que ver el mismo RFC genérico en todos los CFDI.
     */
    @Column(name = "prospect_id")
    private UUID prospectId;

    @Column(name = "party_type", nullable = false, length = 20)
    private String partyType;

    @Column(length = 13)
    private String rfc;

    @Column(name = "tax_name", length = 300)
    private String taxName;

    @Column(name = "tax_regime", length = 10)
    private String taxRegime;

    @Column(name = "tax_zip_code", length = 5)
    private String taxZipCode;

    @Column(name = "cfdi_use", length = 10)
    private String cfdiUse;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FiscalProfile() {}

    public static FiscalProfile of(UUID partyId, UUID prospectId, String partyType, String rfc,
                                    String taxName, String taxRegime, String taxZipCode, String cfdiUse) {
        FiscalProfile f = new FiscalProfile();
        f.partyId = partyId;
        f.prospectId = prospectId;
        f.upsert(partyType, rfc, taxName, taxRegime, taxZipCode, cfdiUse);
        return f;
    }

    /** El prospecto puede llegar en un evento posterior al alta del perfil; no se pierde ni se pisa con nulo. */
    public void linkProspect(UUID prospectId) {
        if (prospectId != null) this.prospectId = prospectId;
    }

    public void upsert(String partyType, String rfc, String taxName, String taxRegime,
                       String taxZipCode, String cfdiUse) {
        this.partyType  = partyType;
        this.rfc        = rfc;
        this.taxName    = taxName;
        this.taxRegime  = taxRegime;
        this.taxZipCode = taxZipCode;
        this.cfdiUse    = cfdiUse;
        this.updatedAt  = Instant.now();
    }

    public UUID getPartyId()      { return partyId; }
    public UUID getProspectId()   { return prospectId; }
    public String getPartyType()  { return partyType; }
    public String getRfc()        { return rfc; }
    public String getTaxName()    { return taxName; }
    public String getTaxRegime()  { return taxRegime; }
    public String getTaxZipCode() { return taxZipCode; }
    public String getCfdiUse()    { return cfdiUse; }
}
