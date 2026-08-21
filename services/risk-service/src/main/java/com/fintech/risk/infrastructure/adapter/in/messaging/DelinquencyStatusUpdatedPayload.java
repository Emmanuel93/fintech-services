package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound from credit-portfolio ({@code credit-portfolio.delinquency-status-updated}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DelinquencyStatusUpdatedPayload(
        UUID creditAccountId,
        int daysDelinquent
) {}
