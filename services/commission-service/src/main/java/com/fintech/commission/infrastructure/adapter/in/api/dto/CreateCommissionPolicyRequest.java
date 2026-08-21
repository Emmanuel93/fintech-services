package com.fintech.commission.infrastructure.adapter.in.api.dto;

import com.fintech.commission.domain.CommissionType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateCommissionPolicyRequest(
        @NotBlank String productType,
        UUID distributorPartyId,     // null = tasa por defecto del producto
        @NotNull CommissionType commissionType,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal rate
) {}
