package com.fintech.origination.application;

import com.fintech.origination.domain.ProductType;

import java.math.BigDecimal;
import java.util.UUID;

public record StartCreditApplicationCommand(
        UUID prospectId,
        ProductType productType,
        BigDecimal requestedAmount,
        Integer requestedTerm,
        String correlationId,
        String promoterCode
) {}
