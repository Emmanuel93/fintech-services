package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/** What accounting reads of {@code credit-portfolio.credit-account-cancelled} (D63). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountCancelledPayload(UUID creditAccountId, UUID obligorPartyId, String reason,
                                            Instant occurredOn) {}
