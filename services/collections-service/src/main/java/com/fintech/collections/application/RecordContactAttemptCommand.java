package com.fintech.collections.application;

import com.fintech.collections.domain.ContactChannel;
import com.fintech.collections.domain.ContactResult;
import java.util.UUID;

public record RecordContactAttemptCommand(
        UUID caseId,
        ContactChannel channel,
        ContactResult result,
        String agentId
) {}
