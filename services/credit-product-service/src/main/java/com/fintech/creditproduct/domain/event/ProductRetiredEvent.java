package com.fintech.creditproduct.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a product version transitions ACTIVE → RETIRED (superseded by newer version).
 * Consumed by origination to remove the old version from its local read model.
 */
public record ProductRetiredEvent(
        UUID productDefinitionId,
        String productCode,
        int retiredVersion,
        int newVersion,
        Instant retiredAt
) {}
