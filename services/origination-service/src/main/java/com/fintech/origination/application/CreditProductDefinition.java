package com.fintech.origination.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model projection of a credit-product-service catalog entry.
 * Fields match {@code CreditProductDefinitionResponse} from the catalog service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditProductDefinition(
        UUID productDefinitionId,
        String productCode,
        int productVersion,
        String productType,
        String behavior,
        String name,
        String status,
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
        String amortizationType,
        String defaultPaymentFrequency,
        Integer minApprovalScore,
        String defaultApprovalFlow,
        BigDecimal openingFeeRate,
        BigDecimal prepaymentFeeRate
) {}
