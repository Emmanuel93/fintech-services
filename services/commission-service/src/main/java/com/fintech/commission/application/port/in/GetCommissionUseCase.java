package com.fintech.commission.application.port.in;

import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.LiquidationBatch;

import java.util.List;
import java.util.UUID;

public interface GetCommissionUseCase {
    List<CommissionRecord> getByCreditAccountId(UUID creditAccountId);
    List<CommissionRecord> getPendingByBeneficiary(UUID beneficiaryPartyId);
    /** The lots of a period, with their taxes and where their SPEI stands. */
    List<LiquidationBatch> getLots(String period);
}
