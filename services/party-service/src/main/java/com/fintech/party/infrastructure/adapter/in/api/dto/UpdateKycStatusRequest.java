package com.fintech.party.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public record UpdateKycStatusRequest(
        @NotBlank
        @Pattern(regexp = "INE|PASSPORT|RFC|CURP|ACTA_CONSTITUTIVA|PODER_NOTARIAL")
        String documentType,

        @NotBlank
        @Pattern(regexp = "PENDING|IN_PROGRESS|VERIFIED|REJECTED")
        String verificationStatus,

        String verifiedBy,
        String documentRef,
        LocalDate expiresAt,
        String rejectionReason
) {}
