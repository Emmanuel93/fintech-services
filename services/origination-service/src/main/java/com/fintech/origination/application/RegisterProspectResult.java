package com.fintech.origination.application;

import java.time.Instant;
import java.util.UUID;

public sealed interface RegisterProspectResult {

    record ProspectRegistered(
            UUID prospectId,
            String curp,
            String phone,
            Instant createdAt,
            Instant expiresAt
    ) implements RegisterProspectResult {}
}
