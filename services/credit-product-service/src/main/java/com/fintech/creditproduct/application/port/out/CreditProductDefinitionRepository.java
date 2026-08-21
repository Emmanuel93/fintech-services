package com.fintech.creditproduct.application.port.out;

import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.ProductStatus;
import com.fintech.creditproduct.domain.ProductType;
import com.fintech.creditproduct.domain.TargetAudience;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditProductDefinitionRepository {

    CreditProductDefinition save(CreditProductDefinition definition);

    /** Forces pending changes to the DB — used to order the retire→activate writes (PD-01). */
    void flush();

    Optional<CreditProductDefinition> findById(UUID productDefinitionId);

    /** Todas las definiciones, cualquier estado — para la administración del catálogo (backoffice). */
    List<CreditProductDefinition> findAll();

    /** Returns the currently ACTIVE version of a productCode, if any. */
    Optional<CreditProductDefinition> findActiveByProductCode(String productCode);

    /** Returns all versions of a productCode ordered by productVersion descending. */
    List<CreditProductDefinition> findVersionHistory(String productCode);

    /** Returns the maximum productVersion for a given productCode (0 if none exists). */
    int findMaxVersionByProductCode(String productCode);

    List<CreditProductDefinition> findByStatus(ProductStatus status);

    List<CreditProductDefinition> findByStatusAndProductType(ProductStatus status, ProductType productType);

    List<CreditProductDefinition> findByStatusAndTargetAudience(ProductStatus status, TargetAudience targetAudience);

    List<CreditProductDefinition> findByStatusAndProductTypeAndTargetAudience(
            ProductStatus status, ProductType productType, TargetAudience targetAudience);

    /** Kept for backward compatibility — returns ACTIVE version or any if none ACTIVE. */
    Optional<CreditProductDefinition> findByProductCode(String productCode);

    boolean existsByProductCode(String productCode);
}
