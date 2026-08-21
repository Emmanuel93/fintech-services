package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import com.fintech.disbursement.domain.Beneficiary;
import com.fintech.disbursement.domain.DisbursementOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DisbursementResponse(
        UUID disbursementId,
        UUID companyId,
        String sourceSystem,
        String sourceType,
        String sourceReference,
        String sourceEventId,
        Map<String, String> sourceMetadata,
        String beneficiaryName,
        /** Enmascarada: los cuatro últimos dígitos. Nunca la cuenta completa (§15). */
        String beneficiaryAccountMasked,
        BigDecimal amount,
        String currency,
        String concept,
        String rail,
        String provider,
        String status,
        String externalRef,
        String receiptUrl,
        String failureCode,
        String failureReason,
        int attemptCount,
        Instant scheduledFor,
        Instant createdAt,
        Instant dispatchedAt,
        Instant settledAt
) {

    public static DisbursementResponse from(DisbursementOrder order) {
        Beneficiary beneficiary = order.getBeneficiary();
        return new DisbursementResponse(
                order.getDisbursementId(), order.getCompanyId(), order.getSourceSystem(),
                order.getSourceType(), order.getSourceReference(), order.getSourceEventId(),
                order.getSourceMetadata(), beneficiary.getName(),
                Beneficiary.mask(beneficiary.getAccount()), order.getAmount(), order.getCurrency(),
                order.getConcept(), order.getRail(), order.getProvider(), order.getStatus(),
                order.getExternalRef(), order.getCepUrl(), order.getFailureCode(),
                order.getFailureReason(), order.getAttemptCount(), order.getScheduledFor(),
                order.getCreatedAt(), order.getDispatchedAt(), order.getSettledAt());
    }
}
