package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditProductCreationRequestedPayload(
        String eventId,
        UUID applicationId,
        String contractNumber,
        UUID obligorPartyId,
        String productCode,
        Integer productVersion,
        String productType,
        String productBehavior,
        BigDecimal approvedAmount,
        BigDecimal approvedLine,
        Integer assignedTerm,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate,
        String amortizationType,
        BigDecimal openingFeeRate,
        String clabeAccount,
        String riskTier,
        String promoterCode,
        String obligorName,
        String obligorTaxId,
        /** Días de BNPL que el cliente pidió al firmar; nulo si no pidió ninguno. */
        Integer bnplDeferralDays
) {}
