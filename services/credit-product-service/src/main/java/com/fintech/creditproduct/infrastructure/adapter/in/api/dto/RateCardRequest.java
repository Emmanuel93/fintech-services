package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Tiered pricing entry sent in CreateCreditProductRequest.
 * All band fields are optional — null means "any value matches".
 */
public record RateCardRequest(

        /** Risk tier (e.g. "T1", "T2") — null = applies to any tier. */
        String tierBand,

        @DecimalMin("0.01") BigDecimal minAmount,
        @DecimalMin("0.01") BigDecimal maxAmount,

        Integer minTerm,
        Integer maxTerm,

        @NotNull @DecimalMin("0.0001") BigDecimal nominalRate,
        @NotNull @DecimalMin("0.0001") BigDecimal moratoriumRate
) {}
