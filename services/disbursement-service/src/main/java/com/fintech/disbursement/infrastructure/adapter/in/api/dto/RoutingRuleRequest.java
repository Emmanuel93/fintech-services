package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.UUID;

/** Nulo en {@code companyId} = regla por defecto para todas las empresas. */
public record RoutingRuleRequest(
        UUID companyId,
        @NotBlank String rail,
        @NotBlank String provider,
        @PositiveOrZero BigDecimal minAmount,
        BigDecimal maxAmount,
        int priority
) {}
