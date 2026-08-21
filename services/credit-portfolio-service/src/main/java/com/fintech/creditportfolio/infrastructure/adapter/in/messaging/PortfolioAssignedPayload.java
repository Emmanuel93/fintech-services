package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound {@code sales-org.portfolio-assigned} — la sucursal a la que se atribuye el crédito. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioAssignedPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        UUID executiveStaffId,
        String unitCode,
        int nivelEscalado
) {}
