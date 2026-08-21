package com.fintech.party.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound {@code sales-org.portfolio-assigned} — a quién le tocó gestionar una cartera. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioAssignedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        UUID executiveStaffId,
        String unitCode,
        int nivelEscalado
) {}
