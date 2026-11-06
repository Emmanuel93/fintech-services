package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
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
        Capabilities capabilities,
        /** The product's billing cycle; absent from events published before the catalog declared it. */
        BillingCycle billingCycle,
        /** The product's amount and term ranges; absent from older events. */
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTerm,
        Integer maxTerm,
        Integer termStep,
        /** The distributor's commission terms; null for every product but a distributor line. */
        DistributorCommission distributorCommission
) {}
