package com.fintech.origination.infrastructure.adapter.in.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ProspectResponse(
        UUID prospectId,
        String curp,
        String phone,
        Instant createdAt,
        Instant expiresAt,
        String message
) {
    public static ProspectResponse from(UUID prospectId, String curp, String phone,
                                        Instant createdAt, Instant expiresAt) {
        return new ProspectResponse(prospectId, curp, phone, createdAt, expiresAt,
                "Prospect registered successfully. KYC flow will be initiated.");
    }
}
