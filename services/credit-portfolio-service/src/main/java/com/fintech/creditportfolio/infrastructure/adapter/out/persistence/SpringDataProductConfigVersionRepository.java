package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataProductConfigVersionRepository extends JpaRepository<ProductConfigVersion, UUID> {

    Optional<ProductConfigVersion> findByProductCodeAndProductVersion(String productCode, int productVersion);

    Optional<ProductConfigVersion> findFirstByProductCodeAndStatusOrderByProductVersionDesc(
            String productCode, String status);

    List<ProductConfigVersion> findByProductCodeOrderByProductVersionDesc(String productCode);
}
