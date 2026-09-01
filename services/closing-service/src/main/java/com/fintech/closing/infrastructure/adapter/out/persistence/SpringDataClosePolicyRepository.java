package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.ClosePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface SpringDataClosePolicyRepository extends JpaRepository<ClosePolicy, UUID> {

    /**
     * Trae las candidatas de los tres alcances en una sola consulta. La precedencia NO se resuelve
     * aquí: eso es del dominio, y ordenarlo en SQL escondería la regla de negocio en un ORDER BY.
     */
    @Query("""
            SELECT p FROM ClosePolicy p
             WHERE p.status = 'ACTIVE'
               AND ( p.scopeType = 'GLOBAL'
                  OR (p.scopeType = 'PRODUCT'      AND p.scopeValue IN :productIds)
                  OR (p.scopeType = 'PRODUCT_TYPE' AND p.scopeValue IN :productTypes) )
            """)
    List<ClosePolicy> findActiveForScopes(@Param("productIds") List<String> productIds,
                                           @Param("productTypes") List<String> productTypes);

    List<ClosePolicy> findByStatus(String status);
}
