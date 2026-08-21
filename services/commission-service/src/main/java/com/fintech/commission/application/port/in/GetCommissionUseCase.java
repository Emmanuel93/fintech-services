package com.fintech.commission.application.port.in;

import com.fintech.commission.domain.CommissionRecord;

import java.util.List;
import java.util.UUID;

public interface GetCommissionUseCase {
    List<CommissionRecord> getByCreditAccountId(UUID creditAccountId);
    List<CommissionRecord> getPendingByBeneficiary(UUID beneficiaryPartyId);
}
