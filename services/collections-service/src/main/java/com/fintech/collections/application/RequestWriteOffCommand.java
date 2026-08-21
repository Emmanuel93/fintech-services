package com.fintech.collections.application;

import com.fintech.collections.domain.WriteOffReason;
import java.util.UUID;

public record RequestWriteOffCommand(
        UUID caseId,
        WriteOffReason reason,
        String requestedBy
) {}
