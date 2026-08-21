package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.DelinquencyBucket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaCollectionCaseRepository
        extends JpaRepository<CollectionCase, UUID>, CollectionCaseRepository {

    @Override
    @Query("SELECT c FROM CollectionCase c WHERE c.creditAccountId = :creditAccountId " +
           "AND c.status IN ('OPEN','MANAGED','LEGAL')")
    Optional<CollectionCase> findActiveByCreditAccountId(@Param("creditAccountId") UUID creditAccountId);

    @Override
    List<CollectionCase> findByStatusInAndDaysDelinquentGreaterThanEqual(List<CaseStatus> statuses, int daysDelinquent);

    /**
     * Cada filtro se ignora cuando llega nulo, con la guarda {@code :x IS NULL OR …}. Así una sola
     * consulta sirve a la bandeja sin filtros y a la más acotada, sin construir el JPQL a mano.
     */
    @Override
    @Query("SELECT c FROM CollectionCase c "
         + "WHERE (:status IS NULL OR c.status = :status) "
         + "  AND (:bucket IS NULL OR c.currentBucket = :bucket) "
         + "  AND (:productType IS NULL OR c.productType = :productType) "
         + "  AND (:assignedAgentId IS NULL OR c.assignedAgentId = :assignedAgentId) "
         + "  AND (:minDays IS NULL OR c.daysDelinquent >= :minDays)")
    Page<CollectionCase> search(@Param("status") CaseStatus status,
                                @Param("bucket") DelinquencyBucket bucket,
                                @Param("productType") String productType,
                                @Param("assignedAgentId") String assignedAgentId,
                                @Param("minDays") Integer minDaysDelinquent,
                                Pageable pageable);
}
