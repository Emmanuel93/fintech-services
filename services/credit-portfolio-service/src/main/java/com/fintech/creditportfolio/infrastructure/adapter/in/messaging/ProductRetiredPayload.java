package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Projection of credit-product's {@code ProductRetiredEvent}
 * (topic {@code product-catalog.product-retired}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductRetiredPayload(
        String productCode,
        int retiredVersion,
        int newVersion
) {}
