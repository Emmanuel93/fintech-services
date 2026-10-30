package com.fintech.disbursement.infrastructure.adapter.out.persistence;

import com.fintech.disbursement.domain.DisbursementOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataDisbursementOrderRepository extends JpaRepository<DisbursementOrder, UUID> {

    Optional<DisbursementOrder> findBySourceSystemAndSourceTypeAndSourceEventId(
            String sourceSystem, String sourceType, String sourceEventId);

    /**
     * {@code FOR UPDATE SKIP LOCKED}: cada réplica toma un lote distinto sin bloquearse. Es lo que
     * permite escalar el despacho horizontalmente sin traer un coordinador externo al monorepo.
     *
     * <p>El {@code lock.timeout = -2} es el valor de Hibernate para {@code SKIP LOCKED}.
     */
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT o FROM DisbursementOrder o
            WHERE o.status = 'REQUESTED'
              AND (o.scheduledFor IS NULL OR o.scheduledFor <= :now)
            ORDER BY o.createdAt
            """)
    List<DisbursementOrder> lockDueForDispatch(@Param("now") Instant now, Pageable pageable);

    List<DisbursementOrder> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /**
     * Nativa porque el crédito vive en el JSON opaco de {@code source_metadata}: JPQL no lo alcanza
     * sin traer el núcleo a hablar de crédito. Los {@code CAST} no son ruido — sin ellos Postgres no
     * sabe el tipo de un parámetro nulo y la consulta falla justo en el caso «sin filtro».
     */
    @Query(value = """
            SELECT * FROM disbursement.disbursement_orders o
             WHERE (CAST(:creditAccountId AS text) IS NULL
                    OR o.source_metadata ->> 'creditAccountId' = CAST(:creditAccountId AS text))
               AND (CAST(:q AS text) IS NULL
                    OR o.beneficiary_name ILIKE CONCAT('%', CAST(:q AS text), '%')
                    OR o.external_ref     ILIKE CONCAT('%', CAST(:q AS text), '%'))
             ORDER BY o.created_at DESC, o.disbursement_id
            """,
            countQuery = """
            SELECT count(*) FROM disbursement.disbursement_orders o
             WHERE (CAST(:creditAccountId AS text) IS NULL
                    OR o.source_metadata ->> 'creditAccountId' = CAST(:creditAccountId AS text))
               AND (CAST(:q AS text) IS NULL
                    OR o.beneficiary_name ILIKE CONCAT('%', CAST(:q AS text), '%')
                    OR o.external_ref     ILIKE CONCAT('%', CAST(:q AS text), '%'))
            """,
            nativeQuery = true)
    Page<DisbursementOrder> search(@Param("creditAccountId") String creditAccountId,
                                   @Param("q") String q,
                                   Pageable pageable);
}
