package com.fintech.creditproduct.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Capability matrix snapshot — governs credit-portfolio motor behaviour.
 * Stored as JSONB so product managers can tune features per definition version
 * without code changes.  Defaults are derived from productType via {@link #defaultFor}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Capabilities(

        /** INSTALLMENT: amortization schedule is generated on first disbursement. */
        boolean hasAmortizationSchedule,

        /** REVOLVING: creditLimit and availableCredit are tracked. */
        boolean hasCreditLimit,

        /** REVOLVING: more than one active disposition is permitted. */
        boolean allowsMultipleDispositions,

        /** SELF_USE | THIRD_PARTY_CREDIT | PAYROLL */
        String dispositionType,

        /** REVOLVING: cutoff date logic and account statement cycle are active. */
        boolean hasCutoffDate,

        /** REVOLVING: minimum payment calculation applies. */
        boolean hasMinimumPayment,

        /** GROUP_LOAN: multiple obligor party IDs are tracked. */
        boolean allowsMultipleObligors,

        /** DISTRIBUTOR_LINE: commission accrual is enabled (T6). */
        boolean commissionsEnabled,

        /** DISTRIBUTOR_LINE: each disposition must carry a beneficiaryPartyId. */
        boolean requiresBeneficiaryPartyId

) {

    /** Returns sensible defaults derived from productType. */
    public static Capabilities defaultFor(ProductType type) {
        return switch (type) {
            case PERSONAL_LOAN, MICRO_LOAN ->
                    new Capabilities(true, false, false, "SELF_USE", false, false, false, false, false);
            case PAYROLL_LOAN ->
                    new Capabilities(true, false, false, "PAYROLL", false, false, false, false, false);
            case GROUP_LOAN ->
                    new Capabilities(true, false, false, "SELF_USE", false, false, true, false, false);
            case REVOLVING_LINE, CREDIT_CARD ->
                    new Capabilities(false, true, true, "SELF_USE", true, true, false, false, false);
            case DISTRIBUTOR_LINE ->
                    new Capabilities(false, true, true, "THIRD_PARTY_CREDIT", true, true, false, true, true);
            case SME_LOAN ->
                    new Capabilities(true, false, false, "SELF_USE", false, false, false, false, false);
            case BUSINESS_REVOLVING_LINE ->
                    new Capabilities(false, true, true, "SELF_USE", true, true, false, false, false);
        };
    }
}
