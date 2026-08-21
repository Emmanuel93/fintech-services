package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Projection of credit-portfolio-service's CreditAccountActivatedEvent.
 * Only contractId (= applicationId) is needed to close the loop in origination.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        String eventId,
        UUID contractId,
        UUID creditAccountId
) {}
