package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingSource;

/** Announces a funding movement once it is committed: accounting books it, the bank reconciliation matches it. */
public interface FundingEventPublisher {

    void movementRecorded(FundingSource source, FundingMovement movement);
}
