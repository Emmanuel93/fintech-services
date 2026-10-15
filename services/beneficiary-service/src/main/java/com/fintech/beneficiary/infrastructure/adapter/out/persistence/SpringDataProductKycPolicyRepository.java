package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.ProductKycPolicyRepository;
import com.fintech.beneficiary.domain.ProductKycPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataProductKycPolicyRepository
        extends JpaRepository<ProductKycPolicy, String>, ProductKycPolicyRepository {
}
