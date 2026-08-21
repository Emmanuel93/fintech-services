package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound from credit-portfolio ({@code credit-portfolio.credit-account-activated}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType
) {}
