package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code credit-portfolio.credit-account-activated} — contractId == applicationId de origination. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID contractId,
        UUID obligorPartyId,
        String productType,
        BigDecimal creditLimit,
        BigDecimal nominalRate
) {}
