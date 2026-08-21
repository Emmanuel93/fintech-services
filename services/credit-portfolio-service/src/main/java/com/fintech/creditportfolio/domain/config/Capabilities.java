package com.fintech.creditportfolio.domain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Capability matrix — mirror of credit-product's Capabilities, received via the
 * {@code product-catalog.product-activated} event. Governs how the portfolio motor
 * behaves for accounts originated under a given product config version.
 *
 * <p>This is a projection (local read-model), not a shared model — it only carries
 * the fields the portfolio engine needs to drive behaviour.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Capabilities(
        boolean hasAmortizationSchedule,
        boolean hasCreditLimit,
        boolean allowsMultipleDispositions,
        String dispositionType,            // SELF_USE | THIRD_PARTY_CREDIT | PAYROLL
        boolean hasCutoffDate,
        boolean hasMinimumPayment,
        boolean allowsMultipleObligors,
        boolean commissionsEnabled,
        boolean requiresBeneficiaryPartyId
) {
    /**
     * Conservative defaults derived from product behavior, used only as a degraded
     * fallback when the authoritative config version has not yet propagated.
     */
    public static Capabilities degradedFor(String behavior, String dispositionType) {
        boolean revolving = "REVOLVING".equalsIgnoreCase(behavior);
        return new Capabilities(
                !revolving,                 // installment → has schedule
                revolving,                  // revolving → has credit limit
                revolving,                  // revolving → multiple dispositions
                dispositionType != null ? dispositionType : "SELF_USE",
                revolving,                  // revolving → cutoff date
                revolving,                  // revolving → minimum payment
                false, false, false);
    }
}
