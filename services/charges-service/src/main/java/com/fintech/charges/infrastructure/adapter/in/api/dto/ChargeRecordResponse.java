package com.fintech.charges.infrastructure.adapter.in.api.dto;

import com.fintech.charges.domain.ChargeRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ChargeRecordResponse(
        UUID chargeId,
        UUID creditAccountId,
        String chargeType,
        String status,
        BigDecimal basis,
        BigDecimal rate,
        Integer days,
        BigDecimal amount,
        BigDecimal taxAmount,
        BigDecimal totalAmount,
        LocalDate accrualDate,
        UUID linkedChargeId,
        String reversalReason,
        String waivedBy,
        Instant createdAt
) {
    public static ChargeRecordResponse from(ChargeRecord r) {
        return new ChargeRecordResponse(
                r.getChargeId(), r.getCreditAccountId(), r.getChargeType(), r.getStatus(),
                r.getBasis(), r.getRate(), r.getDays(), r.getAmount(), r.getTaxAmount(),
                r.getTotalAmount(), r.getAccrualDate(), r.getLinkedChargeId(),
                r.getReversalReason(), r.getWaivedBy(), r.getCreatedAt());
    }
}
