package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.ProductKycPolicy;

import java.util.Optional;

/** La política de KYC vigente por producto, según la última activación que publicó el catálogo. */
public interface ProductKycPolicyRepository {

    Optional<ProductKycPolicy> findByProductCode(String productCode);

    ProductKycPolicy save(ProductKycPolicy policy);
}
