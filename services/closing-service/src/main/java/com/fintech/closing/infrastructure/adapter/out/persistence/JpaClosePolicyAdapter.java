package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.ClosePolicyRepository;
import com.fintech.closing.domain.ClosePolicy;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class JpaClosePolicyAdapter implements ClosePolicyRepository {

    private final SpringDataClosePolicyRepository jpa;

    JpaClosePolicyAdapter(SpringDataClosePolicyRepository jpa) { this.jpa = jpa; }

    @Override
    public List<ClosePolicy> findActiveForScopes(List<String> productIds, List<String> productTypes) {
        // `IN ()` vacío no es SQL válido en Postgres; un centinela que no puede colisionar con un
        // UUID ni con un tipo de producto mantiene la consulta única en vez de ramificarla en tres.
        return jpa.findActiveForScopes(
                productIds.isEmpty()   ? List.of("__none__") : productIds,
                productTypes.isEmpty() ? List.of("__none__") : productTypes);
    }

    @Override
    public List<ClosePolicy> findAllActive() { return jpa.findByStatus("ACTIVE"); }
}
