package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.application.port.out.PartyRoleRepository;
import com.fintech.party.domain.PartyRole;
import com.fintech.party.domain.PartyRoleType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPartyRoleAdapter implements PartyRoleRepository {

    private final SpringDataPartyRoleRepository jpa;

    JpaPartyRoleAdapter(SpringDataPartyRoleRepository jpa) {
        this.jpa = jpa;
    }

    @Override public PartyRole save(PartyRole role) { return jpa.save(role); }

    @Override
    public List<PartyRole> findActiveByPartyId(UUID partyId) {
        return jpa.findByPartyIdAndActiveTrue(partyId);
    }

    @Override
    public Optional<PartyRole> findActiveByPartyIdAndRoleType(UUID partyId, PartyRoleType roleType) {
        return jpa.findByPartyIdAndRoleTypeAndActiveTrue(partyId, roleType);
    }

    @Override
    public boolean existsActive(UUID partyId, PartyRoleType roleType) {
        return jpa.existsByPartyIdAndRoleTypeAndActiveTrue(partyId, roleType);
    }

    @Override
    public List<UUID> findActivePartyIdsByRole(PartyRoleType roleType) {
        return jpa.findActivePartyIdsByRole(roleType);
    }
}
