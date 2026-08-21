package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fintech.stp.domain.StpCompany;

import java.time.Instant;
import java.util.UUID;

public record CompanyResponse(
        UUID companyId,
        String code,
        String stpEmpresa,
        Integer institucionOperante,
        String trackingPrefix,
        String status,
        Instant createdAt
) {

    public static CompanyResponse from(StpCompany company) {
        return new CompanyResponse(company.getCompanyId(), company.getCode(), company.getStpEmpresa(),
                company.getInstitucionOperante(), company.getTrackingPrefix(),
                company.getStatus(), company.getCreatedAt());
    }
}
