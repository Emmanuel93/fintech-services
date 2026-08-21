package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import com.fintech.disbursement.domain.CompanyMapping;

import java.time.Instant;
import java.util.UUID;

public record CompanyMappingResponse(
        UUID companyMappingId,
        String sourceSystem,
        String sourceKey,
        UUID companyId,
        boolean enabled,
        Instant createdAt
) {

    public static CompanyMappingResponse from(CompanyMapping mapping) {
        return new CompanyMappingResponse(mapping.getCompanyMappingId(), mapping.getSourceSystem(),
                mapping.getSourceKey(), mapping.getCompanyId(), mapping.isEnabled(),
                mapping.getCreatedAt());
    }
}
