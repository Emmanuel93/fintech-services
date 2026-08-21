package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.application.ProviderOutcome;

public interface ApplyProviderOutcomeUseCase {

    /** Idempotente: un resultado repetido sobre una orden ya terminal no cambia nada ni truena. */
    void apply(ProviderOutcome outcome);
}
