package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.CommissionRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CommissionRecordResponse(
        UUID commissionId,
        String commissionType,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        BigDecimal basis,
        BigDecimal rate,
        BigDecimal amount,
        String status,
        String period,
        Instant accrualDate,
        UUID liquidationBatchId
) {
    public static CommissionRecordResponse from(CommissionRecord r) {
        return new CommissionRecordResponse(r.getCommissionId(), r.getCommissionType().name(),
                r.getCreditAccountId(), r.getBeneficiaryPartyId(), r.getBasis(), r.getRate(), r.getAmount(),
                r.getStatus().name(), r.getPeriod(), r.getAccrualDate(), r.getLiquidationBatchId());
    }
}
