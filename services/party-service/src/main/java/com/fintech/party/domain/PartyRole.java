package com.fintech.party.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Un rol adicional de un party (I-03). Se otorga y se revoca; revocar no borra, cierra la fila
 * ({@code active=false}, {@code revokedAt}) para conservar el historial.
 */
@Entity
@Table(schema = "party", name = "party_roles")
public class PartyRole {

    @Id
    @Column(name = "role_id", nullable = false, updatable = false)
    private UUID roleId;

    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, updatable = false, length = 30)
    private PartyRoleType roleType;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "granted_by", length = 120, updatable = false)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected PartyRole() {}

    public static PartyRole grant(UUID partyId, PartyRoleType roleType, String grantedBy) {
        PartyRole r = new PartyRole();
        r.roleId    = UUID.randomUUID();
        r.partyId   = partyId;
        r.roleType  = roleType;
        r.active    = true;
        r.grantedBy = grantedBy;
        r.grantedAt = Instant.now();
        return r;
    }

    public void revoke() {
        if (active) {
            this.active = false;
            this.revokedAt = Instant.now();
        }
    }

    public UUID getRoleId()         { return roleId; }
    public UUID getPartyId()        { return partyId; }
    public PartyRoleType getRoleType() { return roleType; }
    public boolean isActive()       { return active; }
    public String getGrantedBy()    { return grantedBy; }
    public Instant getGrantedAt()   { return grantedAt; }
    public Instant getRevokedAt()   { return revokedAt; }
}
