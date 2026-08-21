package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado final del join de 3 pasos (ver T2_notifications.md §Prerequisito crítico): a partir
 * de aquí, cualquier evento post-activación que traiga {@code obligorPartyId} directo ya resuelve
 * contacto sin volver a hacer el join. {@code firstName} viaja desde ProspectContactShadow para
 * personalizar el copy ({@code {{nombre}}}).
 */
@Entity
@Table(name = "party_contact_directory", schema = "notifications")
public class PartyContactDirectory {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "first_name")
    private String firstName;

    private String phone;
    private String email;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    protected PartyContactDirectory() {}

    public static PartyContactDirectory of(UUID partyId, String firstName, String phone, String email) {
        PartyContactDirectory d = new PartyContactDirectory();
        d.partyId    = partyId;
        d.firstName   = firstName;
        d.phone        = phone;
        d.email         = email;
        d.resolvedAt      = Instant.now();
        return d;
    }

    public UUID getPartyId()       { return partyId; }
    public String getFirstName()   { return firstName; }
    public String getPhone()       { return phone; }
    public String getEmail()       { return email; }
    public Instant getResolvedAt() { return resolvedAt; }
}
