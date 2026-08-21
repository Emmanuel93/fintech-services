package com.fintech.creditproduct.domain.event;

import com.fintech.creditproduct.domain.Capabilities;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published when a product definition version transitions DRAFT → ACTIVE.
 * Consumed by origination (catalog read model) and audit.
 */
public record ProductActivatedEvent(
        UUID productDefinitionId,
        String productCode,
        int productVersion,
        String productType,
        String behavior,
        String targetAudience,
        BigDecimal nominalRateAnnual,
        BigDecimal moratoriumRateAnnual,
        Capabilities capabilities,
        Instant activatedAt
) {}
