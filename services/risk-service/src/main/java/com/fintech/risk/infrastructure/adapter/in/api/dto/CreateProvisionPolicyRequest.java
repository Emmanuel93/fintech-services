package com.fintech.risk.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Provision policy body. {@code rates} keyed by bucket name (CURRENT..B181_PLUS) — all 7 required
 * (PP-02, validated in the domain factory).
 */
public record CreateProvisionPolicyRequest(
        @NotBlank String productType,
        @NotNull Map<String, BigDecimal> rates
) {}
