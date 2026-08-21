package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.config.ProductConfigVersion;

import java.util.List;
import java.util.Optional;

public interface ProductConfigVersionRepository {

    ProductConfigVersion save(ProductConfigVersion version);

    Optional<ProductConfigVersion> findByCodeAndVersion(String productCode, int productVersion);

    /** Highest known version for a code (used to resolve "latest" when no pin is given). */
    Optional<ProductConfigVersion> findLatestActiveByCode(String productCode);

    List<ProductConfigVersion> findAllByCode(String productCode);
}
