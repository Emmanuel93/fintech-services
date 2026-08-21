package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.WriteOffReason;
import jakarta.validation.constraints.NotNull;

public record RequestWriteOffRequest(
        @NotNull WriteOffReason reason
) {}
