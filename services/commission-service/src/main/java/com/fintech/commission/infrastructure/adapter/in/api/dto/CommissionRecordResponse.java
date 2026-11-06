package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.CommissionRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        UUID liquidationBatchId,
        /** The business day of the payment it was earned on: what a statement period counts it by. */
        LocalDate valueDate,
        /** The IVA on the commission. */
        BigDecimal ivaAmount,
        /** {@code NETTED} (settled against the distributor's statement) or {@code PAYOUT}; null on older records. */
        String settlement,
        /** A netted commission: the statement it was netted against, its due date and how late the payment landed. */
        Integer cycleNumber,
        LocalDate dueDate,
        Integer daysLate
) {
    public static CommissionRecordResponse from(CommissionRecord r) {
        return new CommissionRecordResponse(r.getCommissionId(), r.getCommissionType().name(),
                r.getCreditAccountId(), r.getBeneficiaryPartyId(), r.getBasis(), r.getRate(), r.getAmount(),
                r.getStatus().name(), r.getPeriod(), r.getAccrualDate(), r.getLiquidationBatchId(),
                r.getValueDate(), r.getIvaAmount(), r.getSettlement(), r.getCycleNumber(), r.getDueDate(), r.getDaysLate());
    }
}
