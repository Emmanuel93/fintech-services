package com.fintech.salesorg.application.port.out;

import com.fintech.salesorg.application.EffectivePlacementPolicy;

/** Publishes the resolved placement policy of a unit ({@code sales-org.placement-policy-changed}). */
public interface PlacementPolicyEventPublisher {

    void publishChanged(EffectivePlacementPolicy policy);
}
