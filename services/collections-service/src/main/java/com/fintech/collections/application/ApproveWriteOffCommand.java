package com.fintech.collections.application;

import com.fintech.collections.domain.WriteOffReason;
import java.util.UUID;

public record ApproveWriteOffCommand(
        UUID caseId,
        WriteOffReason reason,
        String authorizedBy,
        String authorizationRef
) {}
