package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Proyección ACL de {@code credit-portfolio.delinquency-status-updated}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DelinquencyStatusUpdatedPayload(
        UUID creditAccountId,
        int daysDelinquent) {}
