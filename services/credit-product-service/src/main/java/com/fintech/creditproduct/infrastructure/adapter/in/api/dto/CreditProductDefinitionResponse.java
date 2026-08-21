package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import com.fintech.creditproduct.domain.Capabilities;
import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.RateCard;
import com.fintech.creditproduct.domain.EligibilityRule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record CreditProductDefinitionResponse(

        UUID productDefinitionId,
        String productCode,
        int productVersion,
        String productType,
        String behavior,
        String name,
        String description,
        String status,
        String targetAudience,
        String currency,

        BigDecimal nominalRateAnnual,
        BigDecimal moratoriumRateAnnual,

        Integer minTerm,
        Integer maxTerm,
        Integer defaultTerm,

        BigDecimal minAmount,
        BigDecimal maxAmount,

        BigDecimal defaultCreditLine,
        BigDecimal minCreditLine,
        BigDecimal maxCreditLine,

        /** Minimum amount/line increment — null means no constraint. */
        Integer amountStep,
        Integer termStep,

        String amortizationType,
        String defaultPaymentFrequency,
        Set<String> allowedPaymentFrequencies,

        Integer minApprovalScore,
        String defaultApprovalFlow,

        BigDecimal openingFeeRate,
        BigDecimal prepaymentFeeRate,

        /** Capability matrix — governs credit-portfolio motor behaviour. */
        Capabilities capabilities,

        Set<String> eligiblePartyTypes,
        Set<RequiredDocumentResponse> requiredDocuments,
        Set<String> channelAvailabilities,

        List<RateCardResponse> rateCards,
        List<EligibilityRuleResponse> eligibilityRules,

        Instant createdAt,
        Instant activatedAt,
        Instant retiredAt,
        Instant deprecatedAt
) {
    /** Lightweight factory — no rate cards or eligibility rules loaded (avoid N+1 in list endpoints). */
    public static CreditProductDefinitionResponse from(CreditProductDefinition d) {
        return from(d, List.of(), List.of());
    }

    public static CreditProductDefinitionResponse from(
            CreditProductDefinition d,
            List<RateCard> rateCards,
            List<EligibilityRule> eligibilityRules) {

        return new CreditProductDefinitionResponse(
                d.getProductDefinitionId(),
                d.getProductCode(),
                d.getProductVersion(),
                d.getProductType().name(),
                d.getBehavior().name(),
                d.getName(),
                d.getDescription(),
                d.getStatus().name(),
                d.getTargetAudience().name(),
                d.getCurrency(),
                d.getNominalRateAnnual(),
                d.getMoratoriumRateAnnual(),
                d.getMinTerm(), d.getMaxTerm(), d.getDefaultTerm(),
                d.getMinAmount(), d.getMaxAmount(),
                d.getDefaultCreditLine(), d.getMinCreditLine(), d.getMaxCreditLine(),
                d.getAmountStep(),
                d.getTermStep(),
                d.getAmortizationType() != null ? d.getAmortizationType().name() : null,
                d.getDefaultPaymentFrequency() != null ? d.getDefaultPaymentFrequency().name() : null,
                d.getAllowedPaymentFrequencies().stream().map(Enum::name).collect(Collectors.toSet()),
                d.getMinApprovalScore(),
                d.getDefaultApprovalFlow().name(),
                d.getOpeningFeeRate(),
                d.getPrepaymentFeeRate(),
                d.getCapabilities(),
                d.getEligiblePartyTypes(),
                d.getRequiredDocuments().stream()
                        .map(RequiredDocumentResponse::from)
                        .collect(Collectors.toSet()),
                d.getChannelAvailabilities(),
                rateCards.stream().map(RateCardResponse::from).toList(),
                eligibilityRules.stream().map(EligibilityRuleResponse::from).toList(),
                d.getCreatedAt(),
                d.getActivatedAt(),
                d.getRetiredAt(),
                d.getDeprecatedAt());
    }
}
