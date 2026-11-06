package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.ClientType;
import com.fintech.salesorg.domain.PlacementPolicy;

import java.math.BigDecimal;
import java.util.UUID;

/** A unit's placement policy: what it declares, what applies to it, and where that came from. */
public record PlacementPolicyResponse(
        UUID unitId,
        String code,
        String levelCode,
        String productType,
        boolean configured,
        String inheritedFrom,
        Limits declared,
        Limits effective) {

    public record ClientLimitsDto(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {

        static ClientLimitsDto of(ClientLimits l) {
            return l == null || l.isEmpty() ? null : new ClientLimitsDto(l.maxLoanAmount(), l.maxOutstanding(), l.maxActiveLoans(), l.maxTerm());
        }
    }

    public record Limits(ClientLimitsDto nuevo, ClientLimitsDto revolvente) {

        static Limits of(PlacementPolicy p) {
            if (p == null || p.isEmpty()) return null;
            return new Limits(ClientLimitsDto.of(p.forType(ClientType.NUEVO)), ClientLimitsDto.of(p.forType(ClientType.REVOLVENTE)));
        }
    }

    public static PlacementPolicyResponse from(EffectivePlacementPolicy p) {
        return new PlacementPolicyResponse(p.unitId(), p.unitCode(), p.levelCode(), PlacementPolicy.PRODUCT_TYPE, p.configured(),
                p.inheritedFrom(), Limits.of(p.declared()), Limits.of(p.effective()));
    }
}
