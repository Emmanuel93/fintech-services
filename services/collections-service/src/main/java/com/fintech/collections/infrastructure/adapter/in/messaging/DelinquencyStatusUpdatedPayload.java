package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** Inbound from credit-portfolio (topic {@code credit-portfolio.delinquency-status-updated}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DelinquencyStatusUpdatedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        String contractNumber,
        int daysDelinquent,
        Instant occurredAt
) {}
