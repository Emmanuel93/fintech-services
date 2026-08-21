package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code credit-portfolio.disposition-completed}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispositionCompletedPayload(
        UUID dispositionId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType
) {}
