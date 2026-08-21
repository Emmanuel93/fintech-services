package com.fintech.creditproduct.infrastructure.adapter.in.api.dto;

import com.fintech.creditproduct.domain.RateCard;

import java.math.BigDecimal;
import java.util.UUID;

public record RateCardResponse(
        UUID rateCardId,
        String tierBand,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minTerm,
        Integer maxTerm,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate
) {
    public static RateCardResponse from(RateCard rc) {
        return new RateCardResponse(
                rc.getRateCardId(), rc.getTierBand(),
                rc.getMinAmount(), rc.getMaxAmount(),
                rc.getMinTerm(), rc.getMaxTerm(),
                rc.getNominalRate(), rc.getMoratoriumRate());
    }
}
