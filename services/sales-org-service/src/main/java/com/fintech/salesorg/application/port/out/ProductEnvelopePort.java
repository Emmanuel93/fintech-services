package com.fintech.salesorg.application.port.out;

import com.fintech.salesorg.domain.ProductEnvelope;

import java.util.Optional;

/** The catalog's active distributor line: the envelope every placement policy must fit inside. */
public interface ProductEnvelopePort {

    /** Empty when the catalog has no active DISTRIBUTOR_LINE; throws {@code CatalogUnavailableException} when it cannot be read. */
    Optional<ProductEnvelope> distributorLine();
}
