package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import com.fintech.salesorg.domain.OrgUnit;

import java.time.Instant;
import java.util.UUID;

public record OrgUnitResponse(
        UUID unitId,
        UUID levelId,
        UUID parentUnitId,
        String code,
        String name,
        String path,
        boolean active,
        UUID partyRef,
        String createdBy,
        Instant createdAt) {

    public static OrgUnitResponse from(OrgUnit u) {
        return new OrgUnitResponse(u.getUnitId(), u.getLevelId(), u.getParentUnitId(), u.getCode(),
                u.getName(), u.getPath(), u.isActive(), u.getPartyRef(), u.getCreatedBy(), u.getCreatedAt());
    }
}
