package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inbound {@code origination.offer-presented}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OfferPresentedPayload(
        String eventId,
        UUID applicationId,
        UUID prospectId,
        BigDecimal offeredAmount,
        Integer offeredTerm,
        BigDecimal nominalRate,
        BigDecimal cat,
        Instant validUntil
) {}
