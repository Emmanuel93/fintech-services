package com.fintech.origination.application.port.in;

import java.util.UUID;

/** Marks the application DISBURSED when credit-portfolio activates the account (Phase G loop-close). */
public interface ApplyDisbursementUseCase {
    void apply(UUID applicationId);

    /** The credit was cancelled because its disbursement could not happen (D63): the application closes as failed. */
    void applyDisbursementFailed(UUID applicationId);
}
