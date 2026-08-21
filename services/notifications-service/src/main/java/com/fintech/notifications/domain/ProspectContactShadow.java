package com.fintech.notifications.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Paso 1 del prerequisito de contacto (ver T2_notifications.md §Prerequisito crítico): ningún
 * servicio persiste phone/email contra un partyId — se capturan aquí desde
 * {@code origination.prospect-created}, el único punto del sistema donde ese dato viaja hoy.
 * {@code firstName} se lleva también para personalizar el copy ({@code {{nombre}}}).
 */
@Entity
@Table(name = "prospect_contact_shadow", schema = "notifications")
public class ProspectContactShadow {

    @Id
    @Column(name = "prospect_id", nullable = false, updatable = false)
    private UUID prospectId;

    @Column(name = "first_name")
    private String firstName;

    private String phone;
    private String email;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProspectContactShadow() {}

    public static ProspectContactShadow of(UUID prospectId, String firstName, String phone, String email) {
        ProspectContactShadow s = new ProspectContactShadow();
        s.prospectId = prospectId;
        s.firstName   = firstName;
        s.phone        = phone;
        s.email         = email;
        s.updatedAt      = Instant.now();
        return s;
    }

    public UUID getProspectId()   { return prospectId; }
    public String getFirstName()  { return firstName; }
    public String getPhone()      { return phone; }
    public String getEmail()      { return email; }
    public Instant getUpdatedAt() { return updatedAt; }
}
