package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** What origination reads of {@code credit-portfolio.credit-account-cancelled}: the application that became the credit. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountCancelledPayload(UUID creditAccountId, UUID contractId, String reason) {}
