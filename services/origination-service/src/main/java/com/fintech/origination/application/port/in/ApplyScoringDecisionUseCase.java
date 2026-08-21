package com.fintech.origination.application.port.in;

import com.fintech.origination.application.ApplyScoringDecisionCommand;

public interface ApplyScoringDecisionUseCase {

    /** Applies a scoring decision to the matching active credit application. Idempotent / no-op if none. */
    void apply(ApplyScoringDecisionCommand command);
}
