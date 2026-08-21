package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationStartedPayload(
        UUID intentId,
        UUID partyId,
        UUID channelId,
        String productTypeHint,
        BigDecimal requestedAmount,
        String promoterCode,
        Instant occurredAt
) {}
