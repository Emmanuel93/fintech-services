package com.fintech.salesorg.infrastructure.adapter.out.messaging;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.ClientType;
import com.fintech.salesorg.domain.PlacementPolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code sales-org.placement-policy-changed}: the resolved placement policy of one unit.
 *
 * <p>One message per unit touched, keyed by its code, carrying what applies there once inheritance is resolved.
 * {@code configured=false} means nothing applies any more (the policy above it was cleared). A DISTRIBUTOR node
 * carries her {@code partyRef}: the portfolio keys her copy by it, since a line knows its holder, not her node.
 */
public record PlacementPolicyChangedPayload(
        String eventId,
        UUID unitId,
        String unitCode,
        String path,
        String levelCode,
        UUID partyRef,
        String productType,
        boolean configured,
        String inheritedFrom,
        Limits nuevo,
        Limits revolvente,
        Instant occurredOn) {

    public record Limits(BigDecimal maxLoanAmount, BigDecimal maxOutstanding, Integer maxActiveLoans, Integer maxTerm) {

        static Limits of(ClientLimits l) {
            return l == null || l.isEmpty() ? null : new Limits(l.maxLoanAmount(), l.maxOutstanding(), l.maxActiveLoans(), l.maxTerm());
        }
    }

    public static PlacementPolicyChangedPayload of(EffectivePlacementPolicy p, Instant occurredOn) {
        PlacementPolicy e = p.effective() == null ? PlacementPolicy.EMPTY : p.effective();
        return new PlacementPolicyChangedPayload(UUID.randomUUID().toString(), p.unitId(), p.unitCode(), p.path(),
                p.levelCode(), p.partyRef(), PlacementPolicy.PRODUCT_TYPE, p.configured(), p.inheritedFrom(),
                Limits.of(e.forType(ClientType.NUEVO)), Limits.of(e.forType(ClientType.REVOLVENTE)), occurredOn);
    }
}
