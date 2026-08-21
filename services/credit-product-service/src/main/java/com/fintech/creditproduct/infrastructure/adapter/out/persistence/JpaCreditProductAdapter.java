package com.fintech.creditproduct.infrastructure.adapter.out.persistence;

import com.fintech.creditproduct.application.port.out.CreditProductDefinitionRepository;
import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.ProductStatus;
import com.fintech.creditproduct.domain.ProductType;
import com.fintech.creditproduct.domain.TargetAudience;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaCreditProductAdapter implements CreditProductDefinitionRepository {

    private final SpringDataCreditProductRepository repository;

    public JpaCreditProductAdapter(SpringDataCreditProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public CreditProductDefinition save(CreditProductDefinition definition) {
        return repository.save(definition);
    }

    @Override
    public void flush() {
        repository.flush();
    }

    @Override
    public Optional<CreditProductDefinition> findById(UUID productDefinitionId) {
        return repository.findById(productDefinitionId);
    }

    @Override
    public List<CreditProductDefinition> findAll() {
        return repository.findAll();
    }

    @Override
    public Optional<CreditProductDefinition> findActiveByProductCode(String productCode) {
        return repository.findByProductCodeAndStatus(productCode, ProductStatus.ACTIVE);
    }

    @Override
    public List<CreditProductDefinition> findVersionHistory(String productCode) {
        return repository.findByProductCodeOrderByProductVersionDesc(productCode);
    }

    @Override
    public int findMaxVersionByProductCode(String productCode) {
        return repository.findMaxVersionByProductCode(productCode);
    }

    @Override
    public Optional<CreditProductDefinition> findByProductCode(String productCode) {
        return repository.findByProductCode(productCode);
    }

    @Override
    public boolean existsByProductCode(String productCode) {
        return repository.existsByProductCode(productCode);
    }

    @Override
    public List<CreditProductDefinition> findByStatus(ProductStatus status) {
        return repository.findByStatus(status);
    }

    @Override
    public List<CreditProductDefinition> findByStatusAndProductType(ProductStatus status, ProductType productType) {
        return repository.findByStatusAndProductType(status, productType);
    }

    @Override
    public List<CreditProductDefinition> findByStatusAndTargetAudience(ProductStatus status, TargetAudience targetAudience) {
        return repository.findByStatusAndTargetAudience(status, targetAudience);
    }

    @Override
    public List<CreditProductDefinition> findByStatusAndProductTypeAndTargetAudience(
            ProductStatus status, ProductType productType, TargetAudience targetAudience) {
        return repository.findByStatusAndProductTypeAndTargetAudience(status, productType, targetAudience);
    }
}
