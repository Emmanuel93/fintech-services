package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.AgreementType;
import com.fintech.collections.domain.RestructureTerms;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProposeAgreementRequest(
        @NotNull AgreementType type,
        BigDecimal forgivenAmount,     // QUITA_PARCIAL only
        RestructureTerms newTerms      // RESTRUCTURE only
) {}
