package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Paso 2 del prerequisito de contacto: correlaciona applicationId → prospectId para poder resolver
 * el join final en la activación (contractId de CreditAccountActivated == applicationId).
 *
 * <p>Poblado desde {@code origination.offer-presented} (no desde {@code application-approved} como
 * describía el plan original) — offer-presented ya se consume para la notificación #1 y trae
 * prospectId + applicationId + offeredTerm en un solo evento, evitando un listener adicional solo
 * para la correlación. {@code offeredTerm} alimenta {@link CreditAccountProgress#totalInstallments}.
 */
@Entity
@Table(name = "application_prospect_link", schema = "notifications")
public class ApplicationProspectLink {

    @Id
    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "prospect_id", nullable = false, updatable = false)
    private UUID prospectId;

    @Column(name = "offered_term")
    private Integer offeredTerm;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApplicationProspectLink() {}

    public static ApplicationProspectLink of(UUID applicationId, UUID prospectId, Integer offeredTerm) {
        ApplicationProspectLink l = new ApplicationProspectLink();
        l.applicationId = applicationId;
        l.prospectId     = prospectId;
        l.offeredTerm      = offeredTerm;
        l.updatedAt          = Instant.now();
        return l;
    }

    public UUID getApplicationId()  { return applicationId; }
    public UUID getProspectId()     { return prospectId; }
    public Integer getOfferedTerm() { return offeredTerm; }
    public Instant getUpdatedAt()   { return updatedAt; }
}
