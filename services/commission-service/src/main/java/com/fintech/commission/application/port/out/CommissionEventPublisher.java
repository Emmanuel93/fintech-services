package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.LiquidationBatch;

public interface CommissionEventPublisher {
    void publishCommissionAccrued(CommissionRecord record);
    void publishCommissionReversed(CommissionRecord record);
    void publishCommissionLiquidated(LiquidationBatch batch);
}
