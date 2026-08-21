package com.fintech.origination.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param applicationId  target application (must be APPROVED)
 * @param productCode    specific product definition from credit-product catalog
 * @param offeredAmount  overrides requestedAmount if provided; null → use requestedAmount
 * @param offeredTerm    overrides requestedTerm if provided; null → use product default
 */
public record PresentOfferCommand(
        UUID applicationId,
        String productCode,
        BigDecimal offeredAmount,
        Integer offeredTerm
) {}
