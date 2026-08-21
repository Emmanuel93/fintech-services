package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.ProductConfigVersionRepository;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JpaProductConfigVersionAdapter implements ProductConfigVersionRepository {

    private final SpringDataProductConfigVersionRepository repository;

    public JpaProductConfigVersionAdapter(SpringDataProductConfigVersionRepository repository) {
        this.repository = repository;
    }

    @Override
    public ProductConfigVersion save(ProductConfigVersion version) {
        return repository.save(version);
    }

    @Override
    public Optional<ProductConfigVersion> findByCodeAndVersion(String productCode, int productVersion) {
        return repository.findByProductCodeAndProductVersion(productCode, productVersion);
    }

    @Override
    public Optional<ProductConfigVersion> findLatestActiveByCode(String productCode) {
        return repository.findFirstByProductCodeAndStatusOrderByProductVersionDesc(productCode, "ACTIVE");
    }

    @Override
    public List<ProductConfigVersion> findAllByCode(String productCode) {
        return repository.findByProductCodeOrderByProductVersionDesc(productCode);
    }
}
