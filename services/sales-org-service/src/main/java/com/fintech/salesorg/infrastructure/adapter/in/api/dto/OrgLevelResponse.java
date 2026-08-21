package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import com.fintech.salesorg.domain.OrgLevel;

import java.time.Instant;
import java.util.UUID;

public record OrgLevelResponse(
        UUID levelId,
        int depth,
        String code,
        String name,
        boolean active,
        Instant createdAt) {

    public static OrgLevelResponse from(OrgLevel l) {
        return new OrgLevelResponse(l.getLevelId(), l.getDepth(), l.getCode(), l.getName(),
                l.isActive(), l.getCreatedAt());
    }
}
