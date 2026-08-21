package com.fintech.configuration.infrastructure.adapter.in.api.dto;

import com.fintech.configuration.domain.ConfigParameter;
import com.fintech.configuration.domain.ConfigParameterStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ConfigParameterResponse(
        UUID id,
        String paramKey,
        String value,
        String productType,
        String channelType,
        int version,
        ConfigParameterStatus status,
        LocalDate effectiveDate,
        UUID createdBy,
        UUID approvedBy,
        UUID previousVersionRef,
        Instant createdAt,
        Instant updatedAt
) {
    public static ConfigParameterResponse from(ConfigParameter p) {
        return new ConfigParameterResponse(
                p.getId(), p.getParamKey(), p.getValue(),
                p.getProductType(), p.getChannelType(),
                p.getVersion(), p.getStatus(), p.getEffectiveDate(),
                p.getCreatedBy(), p.getApprovedBy(), p.getPreviousVersionRef(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
