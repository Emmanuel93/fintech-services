package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import com.fintech.creditproduct.domain.BillingCycle;
import com.fintech.creditproduct.domain.Capabilities;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public record CreateCreditProductRequest(

        @NotBlank @Size(max = 50)
        String productCode,

        @NotBlank
        String productType,

        @NotBlank @Size(max = 100)
        String name,

        String description,

        @NotBlank
        String targetAudience,

        @NotBlank @Size(min = 3, max = 3)
        String currency,

        @NotNull @DecimalMin("0.0001") @DecimalMax("0.9999")
        BigDecimal nominalRateAnnual,

        @NotNull @DecimalMin("0.0001") @DecimalMax("0.9999")
        BigDecimal moratoriumRateAnnual,

        // Plazo — null para REVOLVING
        @Min(1) Integer minTerm,
        @Min(1) Integer maxTerm,
        @Min(1) Integer defaultTerm,

        // Montos — null para REVOLVING
        @DecimalMin("0.01") BigDecimal minAmount,
        @DecimalMin("0.01") BigDecimal maxAmount,

        // Línea de crédito — null para INSTALLMENT
        @DecimalMin("0.01") BigDecimal defaultCreditLine,
        @DecimalMin("0.01") BigDecimal minCreditLine,
        @DecimalMin("0.01") BigDecimal maxCreditLine,

        /**
         * Incremento mínimo de monto/línea. Null = sin restricción.
         * INSTALLMENT: los montos aprobados deben ser múltiplos de este valor.
         * REVOLVING: las líneas deben ser múltiplos de este valor.
         * Ejemplos: 500 (micro préstamo), 1000 (personal/SME), 5000 (distribuidor).
         */
        @Min(100) Integer amountStep,

        /** Escalón del plazo. Nulo o menor a 1 se toma como 1 (todos los plazos del rango). */
        @Min(1) Integer termStep,

        // Amortización — null para REVOLVING (FRENCH | GERMAN | BULLET)
        String amortizationType,

        // Frecuencia de pago — null para REVOLVING
        String defaultPaymentFrequency,
        Set<String> allowedPaymentFrequencies,

        @NotNull @Min(0)
        Integer minApprovalScore,

        @NotBlank
        String defaultApprovalFlow,

        @NotNull @DecimalMin("0") @DecimalMax("0.9999")
        BigDecimal openingFeeRate,

        @NotNull @DecimalMin("0") @DecimalMax("0.9999")
        BigDecimal prepaymentFeeRate,

        @NotEmpty
        Set<String> eligiblePartyTypes,

        @NotEmpty @Valid
        Set<RequiredDocumentRequest> requiredDocuments,

        @NotEmpty
        Set<String> channelAvailabilities,

        /**
         * Optional capability override. When null, defaults are derived from productType.
         * Use this to fine-tune behavior for non-standard product configurations
         * (e.g., PERSONAL_LOAN with revolving topup capability in future).
         */
        Capabilities capabilities,

        /**
         * Tiered rate cards. When empty, flat nominalRateAnnual / moratoriumRateAnnual are used.
         * Common patterns:
         *   - B2C personal: rate bands by risk tier (T1/T2/T3)
         *   - B2B2C distributor: rate bands by line amount
         *   - B2B SME: rate bands by term
         */
        @Valid List<RateCardRequest> rateCards,

        /**
         * Per-product eligibility rules evaluated at application creation time.
         * Complement the global scoring policy — product-specific hard limits.
         */
        @Valid List<EligibilityRuleRequest> eligibilityRules,

        /** How the product's accounts cut and pay. When null, the product type's default applies. */
        BillingCycle billingCycle
) {

    /** The request as it was before the billing cycle existed: the product type's default applies. */
    public CreateCreditProductRequest(String productCode, String productType, String name, String description,
                                      String targetAudience, String currency, BigDecimal nominalRateAnnual,
                                      BigDecimal moratoriumRateAnnual, Integer minTerm, Integer maxTerm, Integer defaultTerm,
                                      BigDecimal minAmount, BigDecimal maxAmount, BigDecimal defaultCreditLine,
                                      BigDecimal minCreditLine, BigDecimal maxCreditLine, Integer amountStep, Integer termStep,
                                      String amortizationType, String defaultPaymentFrequency,
                                      Set<String> allowedPaymentFrequencies, Integer minApprovalScore,
                                      String defaultApprovalFlow, BigDecimal openingFeeRate, BigDecimal prepaymentFeeRate,
                                      Set<String> eligiblePartyTypes, Set<RequiredDocumentRequest> requiredDocuments,
                                      Set<String> channelAvailabilities, Capabilities capabilities,
                                      List<RateCardRequest> rateCards, List<EligibilityRuleRequest> eligibilityRules) {
        this(productCode, productType, name, description, targetAudience, currency, nominalRateAnnual,
                moratoriumRateAnnual, minTerm, maxTerm, defaultTerm, minAmount, maxAmount, defaultCreditLine,
                minCreditLine, maxCreditLine, amountStep, termStep, amortizationType, defaultPaymentFrequency,
                allowedPaymentFrequencies, minApprovalScore, defaultApprovalFlow, openingFeeRate, prepaymentFeeRate,
                eligiblePartyTypes, requiredDocuments, channelAvailabilities, capabilities, rateCards,
                eligibilityRules, null);
    }
}
