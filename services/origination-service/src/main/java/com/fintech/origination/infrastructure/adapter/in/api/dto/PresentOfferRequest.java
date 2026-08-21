package com.fintech.origination.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record PresentOfferRequest(
        @NotBlank String productCode,
        @Positive BigDecimal offeredAmount,
        @Positive Integer offeredTerm
) {}
