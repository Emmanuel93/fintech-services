package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.WriteOffRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WriteOffRecordResponse(
        UUID writeOffId,
        UUID caseId,
        UUID creditAccountId,
        BigDecimal principalWrittenOff,
        BigDecimal interestWrittenOff,
        BigDecimal penaltyWrittenOff,
        BigDecimal totalWrittenOff,
        String authorizedBy,
        String authorizationRef,
        String reason,
        boolean bureauReported,
        Instant writeOffDate
) {
    public static WriteOffRecordResponse from(WriteOffRecord w) {
        return new WriteOffRecordResponse(w.getWriteOffId(), w.getCaseId(), w.getCreditAccountId(),
                w.getPrincipalWrittenOff(), w.getInterestWrittenOff(), w.getPenaltyWrittenOff(), w.getTotalWrittenOff(),
                w.getAuthorizedBy(), w.getAuthorizationRef(), w.getReason().name(), w.isBureauReported(), w.getWriteOffDate());
    }
}
