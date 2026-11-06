package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.LiquidationBatch;

public interface CommissionEventPublisher {

    void publishCommissionAccrued(CommissionRecord record);

    /** A reversal record: what a returned payment took back of the commission it names. */
    void publishCommissionReversed(CommissionRecord reversal);

    /** A lot's SPEI requested: its taxes, the net and the account it goes to. {@code attempt} keys the order. */
    void publishLotPayoutRequested(LiquidationBatch lot, BeneficiaryProfile beneficiary, int attempt);

    /** The lot's order failed or came back: what was moved to the SPEI in flight is owed again. */
    void publishLotPayoutFailed(LiquidationBatch lot, int attempt, String reason);
}
