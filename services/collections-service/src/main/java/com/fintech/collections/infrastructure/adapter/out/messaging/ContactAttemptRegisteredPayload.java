package com.fintech.collections.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

record ContactAttemptRegisteredPayload(
        UUID attemptId,
        UUID caseId,
        String channel,
        String result,
        String agentId,
        Instant attemptedAt
) {}
