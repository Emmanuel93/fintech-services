package com.fintech.configuration.application.port.in;

import com.fintech.configuration.domain.ConfigParameter;
import java.util.UUID;

public interface ApproveConfigParameterUseCase {
    /** Checker approves: PENDING_APPROVAL → ACTIVE. Deprecates previous ACTIVE version. */
    ConfigParameter approve(UUID parameterId, UUID approverId);
}
