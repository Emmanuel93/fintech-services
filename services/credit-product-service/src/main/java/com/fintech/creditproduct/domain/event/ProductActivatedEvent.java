package com.fintech.creditproduct.domain.event;

import com.fintech.creditproduct.domain.BillingCycle;
import com.fintech.creditproduct.domain.Capabilities;
import com.fintech.creditproduct.domain.DistributorCommission;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a product definition version transitions DRAFT → ACTIVE.
 * Consumed by origination (catalog read model) and audit.
 */
public record ProductActivatedEvent(
        UUID productDefinitionId,
        String productCode,
        int productVersion,
        String productType,
        String behavior,
        String targetAudience,
        BigDecimal nominalRateAnnual,
        BigDecimal moratoriumRateAnnual,
        Capabilities capabilities,
        Instant activatedAt,
        /** The portfolio amortizes and cuts with these; until now they never left the catalog. */
        String amortizationType,
        String paymentFrequency,
        BigDecimal openingFeeRate,
        Integer amountStep,
        BillingCycle billingCycle,
        /** The product's amount and term ranges: what the portfolio validates a placement against. */
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTerm,
        Integer maxTerm,
        Integer termStep,
        /** The distributor's commission terms; null for every product but a distributor line. */
        DistributorCommission distributorCommission
) {
    /** The event as it was published before the ranges and the commission travelled. */
    public ProductActivatedEvent(UUID productDefinitionId, String productCode, int productVersion, String productType,
                                 String behavior, String targetAudience, BigDecimal nominalRateAnnual,
                                 BigDecimal moratoriumRateAnnual, Capabilities capabilities, Instant activatedAt,
                                 String amortizationType, String paymentFrequency, BigDecimal openingFeeRate,
                                 Integer amountStep, BillingCycle billingCycle) {
        this(productDefinitionId, productCode, productVersion, productType, behavior, targetAudience, nominalRateAnnual,
                moratoriumRateAnnual, capabilities, activatedAt, amortizationType, paymentFrequency, openingFeeRate,
                amountStep, billingCycle, null, null, null, null, null, null);
    }
}
