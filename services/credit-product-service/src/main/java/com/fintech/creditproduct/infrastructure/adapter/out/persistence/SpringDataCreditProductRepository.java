package com.fintech.creditproduct.infrastructure.adapter.out.persistence;

import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.ProductStatus;
import com.fintech.creditproduct.domain.ProductType;
import com.fintech.creditproduct.domain.TargetAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCreditProductRepository extends JpaRepository<CreditProductDefinition, UUID> {

    Optional<CreditProductDefinition> findByProductCodeAndStatus(String productCode, ProductStatus status);

    Optional<CreditProductDefinition> findByProductCode(String productCode);

    boolean existsByProductCode(String productCode);

    List<CreditProductDefinition> findByProductCodeOrderByProductVersionDesc(String productCode);

    @Query("SELECT COALESCE(MAX(d.productVersion), 0) FROM CreditProductDefinition d WHERE d.productCode = :productCode")
    int findMaxVersionByProductCode(String productCode);

    List<CreditProductDefinition> findByStatus(ProductStatus status);

    List<CreditProductDefinition> findByStatusAndProductType(ProductStatus status, ProductType productType);

    List<CreditProductDefinition> findByStatusAndTargetAudience(ProductStatus status, TargetAudience targetAudience);

    List<CreditProductDefinition> findByStatusAndProductTypeAndTargetAudience(
            ProductStatus status, ProductType productType, TargetAudience targetAudience);
}
