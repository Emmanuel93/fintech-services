package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fintech.stp.application.service.AccountHasher;
import com.fintech.stp.domain.StpPaymentOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Vista operativa de una orden. La cuenta del beneficiario va enmascarada. */
public record PaymentOrderResponse(
        UUID stpPaymentOrderId,
        UUID paymentRequestId,
        UUID companyId,
        String trackingKey,
        LocalDate businessDate,
        BigDecimal amount,
        String beneficiaryName,
        String beneficiaryAccountMasked,
        String status,
        String stpOrderId,
        Integer banxicoCode,
        String banxicoReason,
        String errorDetail,
        int attemptCount,
        String cepUrl,
        Boolean beneficiaryNameMatches,
        Instant createdAt,
        Instant sentAt,
        Instant settledAt
) {

    public static PaymentOrderResponse from(StpPaymentOrder order) {
        return new PaymentOrderResponse(
                order.getStpPaymentOrderId(), order.getPaymentRequestId(), order.getCompanyId(),
                order.getTrackingKey(), order.getBusinessDate(), order.getAmount(),
                order.getBeneficiaryName(), AccountHasher.mask(order.getBeneficiaryAccount()),
                order.getStatus(), order.getStpOrderId(), order.getBanxicoCode(),
                order.getBanxicoReason(), order.getErrorDetail(), order.getAttemptCount(),
                order.getCepUrl(), order.getBeneficiaryNameMatches(),
                order.getCreatedAt(), order.getSentAt(), order.getSettledAt());
    }
}
