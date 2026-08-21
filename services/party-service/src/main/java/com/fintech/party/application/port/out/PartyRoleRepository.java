package com.fintech.party.application.port.out;

import com.fintech.party.domain.PartyRole;
import com.fintech.party.domain.PartyRoleType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartyRoleRepository {

    PartyRole save(PartyRole role);

    /** Roles vigentes de un party. */
    List<PartyRole> findActiveByPartyId(UUID partyId);

    /** El rol vigente de un tipo dado para un party, si lo tiene. */
    Optional<PartyRole> findActiveByPartyIdAndRoleType(UUID partyId, PartyRoleType roleType);

    boolean existsActive(UUID partyId, PartyRoleType roleType);

    /** Los partyId con un rol vigente dado (p.ej. todos los distribuidores). */
    List<UUID> findActivePartyIdsByRole(PartyRoleType roleType);
}
