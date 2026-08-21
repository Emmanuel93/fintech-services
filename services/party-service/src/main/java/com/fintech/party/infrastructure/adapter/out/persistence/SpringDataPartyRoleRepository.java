package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.domain.PartyRole;
import com.fintech.party.domain.PartyRoleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPartyRoleRepository extends JpaRepository<PartyRole, UUID> {

    List<PartyRole> findByPartyIdAndActiveTrue(UUID partyId);

    Optional<PartyRole> findByPartyIdAndRoleTypeAndActiveTrue(UUID partyId, PartyRoleType roleType);

    boolean existsByPartyIdAndRoleTypeAndActiveTrue(UUID partyId, PartyRoleType roleType);

    @Query("SELECT r.partyId FROM PartyRole r WHERE r.roleType = :roleType AND r.active = true")
    List<UUID> findActivePartyIdsByRole(@Param("roleType") PartyRoleType roleType);
}
