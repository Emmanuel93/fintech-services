package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.PaymentPromise;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentPromiseResponse(
        UUID promiseId,
        UUID caseId,
        BigDecimal amount,
        LocalDate promisedDate,
        String status,
        String recordedBy,
        Instant createdAt
) {
    public static PaymentPromiseResponse from(PaymentPromise p) {
        return new PaymentPromiseResponse(p.getPromiseId(), p.getCaseId(), p.getAmount(),
                p.getPromisedDate(), p.getStatus().name(), p.getRecordedBy(), p.getCreatedAt());
    }
}
