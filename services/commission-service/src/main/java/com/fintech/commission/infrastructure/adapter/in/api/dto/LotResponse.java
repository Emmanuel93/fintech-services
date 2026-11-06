package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.LiquidationBatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A commission lot with its taxes and where its SPEI stands. */
public record LotResponse(UUID batchId, UUID beneficiaryPartyId, String productType, String period, int records,
                          BigDecimal subtotal, BigDecimal iva, BigDecimal isrWithheld, BigDecimal ivaWithheld,
                          BigDecimal gross, BigDecimal net, String status, int payoutAttempts, String failureReason,
                          String paymentRef, Instant paidAt) {

    public static LotResponse from(LiquidationBatch b) {
        return new LotResponse(b.getBatchId(), b.getBeneficiaryPartyId(), b.getProductType(), b.getPeriod(),
                b.getRecordsCount(), b.getSubtotal(), b.getIvaAmount(), b.getIsrWithheld(), b.getIvaWithheld(),
                b.getTotalAmount(), b.getNetAmount(), b.getStatus().name(), b.getPayoutAttempts(), b.getFailureReason(),
                b.getPaymentRef(), b.getProcessedAt());
    }
}
