package com.fintech.commission.application;

import com.fintech.commission.domain.CommissionType;

import java.math.BigDecimal;
import java.util.UUID;

/** Crea/versiona una CommissionPolicy. distributorPartyId=null → tasa por defecto del producto. */
public record CreateCommissionPolicyCommand(
        String productType,
        UUID distributorPartyId,
        CommissionType commissionType,
        BigDecimal rate
) {}
