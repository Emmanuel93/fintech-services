package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** What notifications reads of {@code credit-portfolio.disposition-failed} (D63). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispositionFailedPayload(UUID dispositionId, UUID creditAccountId, UUID obligorPartyId,
                                       BigDecimal amount, String outcome, boolean accountCancelled) {}
