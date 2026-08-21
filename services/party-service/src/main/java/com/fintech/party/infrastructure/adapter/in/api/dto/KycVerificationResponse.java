package com.fintech.party.infrastructure.adapter.in.api.dto;

import com.fintech.party.domain.KycVerification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record KycVerificationResponse(
        UUID    verificationId,
        UUID    partyId,
        String  documentType,
        String  verificationStatus,
        String  verifiedBy,
        Instant verifiedAt,
        String  rejectionReason,
        String  documentRef,
        LocalDate expiresAt,
        Instant createdAt
) {
    public static KycVerificationResponse from(KycVerification v) {
        return new KycVerificationResponse(
                v.getVerificationId(), v.getPartyId(),
                v.getDocumentType(), v.getVerificationStatus(),
                v.getVerifiedBy(), v.getVerifiedAt(),
                v.getRejectionReason(), v.getDocumentRef(),
                v.getExpiresAt(), v.getCreatedAt()
        );
    }
}
