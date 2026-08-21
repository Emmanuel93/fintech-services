package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.creditportfolio.domain.config.Capabilities;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Projection of credit-product's {@code ProductActivatedEvent}
 * (topic {@code product-catalog.product-activated}).
 *
 * <p>Fields beyond what the portfolio engine needs are ignored. paymentFrequency / amountStep /
 * amortizationType / opening fee are tolerated as optional — credit-product enriches the event
 * over time; missing fields fall back to null and the engine degrades gracefully.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductActivatedPayload(
        UUID productDefinitionId,
        String productCode,
        int productVersion,
        String productType,
        String behavior,
        String targetAudience,
        BigDecimal nominalRateAnnual,
        BigDecimal moratoriumRateAnnual,
        BigDecimal openingFeeRate,
        String amortizationType,
        String paymentFrequency,
        Integer amountStep,
        Capabilities capabilities
) {}
