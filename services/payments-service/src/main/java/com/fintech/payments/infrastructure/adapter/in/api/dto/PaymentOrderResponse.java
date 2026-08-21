package com.fintech.payments.infrastructure.adapter.in.api.dto;

import com.fintech.payments.domain.PaymentOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentOrderResponse(
        UUID paymentOrderId,
        UUID creditAccountId,
        BigDecimal amount,
        String paymentMethod,
        String externalRef,
        String status,
        long snapshotVersion,
        String rejectionReason,
        String reversalReason,
        Instant createdAt,
        Instant confirmedAt,
        Instant rejectedAt,
        Instant reversedAt
) {
    public static PaymentOrderResponse from(PaymentOrder o) {
        return new PaymentOrderResponse(
                o.getPaymentOrderId(), o.getCreditAccountId(), o.getAmount(),
                o.getPaymentMethod(), o.getExternalRef(), o.getStatus(),
                o.getSnapshotVersion(), o.getRejectionReason(), o.getReversalReason(),
                o.getCreatedAt(), o.getConfirmedAt(), o.getRejectedAt(), o.getReversedAt());
    }
}
