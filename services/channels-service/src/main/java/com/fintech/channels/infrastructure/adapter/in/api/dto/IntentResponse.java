package com.fintech.channels.infrastructure.adapter.in.api.dto;

import com.fintech.channels.domain.CustomerIntent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IntentResponse(
        UUID intentId,
        UUID sessionId,
        String intentType,
        String status,
        String routedTo,
        String productTypeHint,
        BigDecimal requestedAmount,
        String abandonReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static IntentResponse from(CustomerIntent ci) {
        return new IntentResponse(
                ci.getIntentId(), ci.getSessionId(), ci.getIntentType(),
                ci.getStatus(), ci.getRoutedTo(),
                ci.getProductTypeHint(), ci.getRequestedAmount(),
                ci.getAbandonReason(), ci.getCreatedAt(), ci.getUpdatedAt());
    }
}
