package com.fintech.party.infrastructure.adapter.in.api.dto;

import com.fintech.party.domain.PartyRole;

import java.time.Instant;
import java.util.UUID;

public record PartyRoleResponse(
        UUID roleId,
        UUID partyId,
        String roleType,
        boolean active,
        String grantedBy,
        Instant grantedAt) {

    public static PartyRoleResponse from(PartyRole r) {
        return new PartyRoleResponse(r.getRoleId(), r.getPartyId(), r.getRoleType().name(),
                r.isActive(), r.getGrantedBy(), r.getGrantedAt());
    }
}
