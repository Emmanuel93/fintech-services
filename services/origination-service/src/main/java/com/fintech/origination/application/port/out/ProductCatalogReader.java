package com.fintech.origination.application.port.out;

import com.fintech.origination.application.CreditProductDefinition;

import java.util.Optional;

/** ACL port — reads a product definition from credit-product-service (catalog). */
public interface ProductCatalogReader {
    Optional<CreditProductDefinition> findByCode(String productCode);
}
