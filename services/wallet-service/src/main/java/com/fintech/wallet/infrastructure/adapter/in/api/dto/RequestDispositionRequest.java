package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RequestDispositionRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank String dispositionType,
        UUID beneficiaryPartyId,   // required for DISTRIBUTOR_LINE
        String payeeAccount,
        /** A cuántos períodos se amortiza esta colocación. Nulo cae al plazo por defecto. */
        Integer termPeriods
) {}
