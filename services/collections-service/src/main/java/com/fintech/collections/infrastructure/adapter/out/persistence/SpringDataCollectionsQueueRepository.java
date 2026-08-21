package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.ContactQueueRow;
import com.fintech.collections.application.PromiseQueueRow;
import com.fintech.collections.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * Las dos bandejas transversales de cobranza, cada una en una sola consulta.
 *
 * <p>{@code ContactAttempt} y {@code PaymentPromise} sólo guardan {@code caseId}: el tramo, el
 * gestor y el obligado viven en {@code CollectionCase}. Como los tres están en la <b>misma base</b>,
 * el cruce es un {@code JOIN} indexado y no hay nada que denormalizar — el patrón C sería
 * sobreingeniería aquí, y el patrón «una llamada por fila» sería el N+1 que el invariante prohíbe.
 *
 * <p>Las fechas nunca se comparan contra {@code NULL}: Postgres no puede inferir el tipo de un
 * parámetro temporal nulo y {@code (:fecha IS NULL OR …)} revienta. Los límites se pasan siempre,
 * con valores por defecto que equivalen a «sin filtro».
 */
interface SpringDataCollectionsQueueRepository extends JpaRepository<PaymentPromise, UUID> {

    /**
     * Promesas de pago cruzando casos.
     *
     * <p>{@code attemptsToday} y {@code lastContactResult} salen de subconsultas correlacionadas.
     * Cuestan un índice por {@code (case_id, attempted_at)} y ahorran una llamada por fila.
     */
    @Query("""
            SELECT new com.fintech.collections.application.PromiseQueueRow(
                       p.promiseId, p.caseId, p.amount, p.promisedDate, p.status,
                       p.recordedBy, p.createdAt,
                       c.creditAccountId, c.obligorPartyId, c.productType,
                       c.currentBucket, c.daysDelinquent, c.assignedAgentId,
                       (SELECT COUNT(a) FROM ContactAttempt a
                         WHERE a.caseId = c.caseId AND a.attemptedAt >= :dayStart),
                       (SELECT a2.result FROM ContactAttempt a2
                         WHERE a2.caseId = c.caseId
                           AND a2.attemptedAt = (SELECT MAX(a3.attemptedAt) FROM ContactAttempt a3
                                                  WHERE a3.caseId = c.caseId)))
              FROM PaymentPromise p
              JOIN CollectionCase c ON c.caseId = p.caseId
             WHERE (:statuses IS NULL OR p.status IN :statuses)
               AND (:buckets  IS NULL OR c.currentBucket IN :buckets)
               AND (:agentIds IS NULL OR c.assignedAgentId IN :agentIds)
               AND p.promisedDate BETWEEN :dueFrom AND :dueTo
            """)
    Page<PromiseQueueRow> searchPromises(@Param("statuses") Collection<PromiseStatus> statuses,
                                         @Param("buckets") Collection<DelinquencyBucket> buckets,
                                         @Param("agentIds") Collection<String> agentIds,
                                         @Param("dueFrom") LocalDate dueFrom,
                                         @Param("dueTo") LocalDate dueTo,
                                         @Param("dayStart") Instant dayStart,
                                         Pageable pageable);

    @Query("""
            SELECT new com.fintech.collections.application.ContactQueueRow(
                       a.attemptId, a.caseId, a.channel, a.result, a.origin,
                       a.agentId, a.dunningStep, a.attemptedAt,
                       c.creditAccountId, c.obligorPartyId, c.productType,
                       c.currentBucket, c.daysDelinquent, c.assignedAgentId)
              FROM ContactAttempt a
              JOIN CollectionCase c ON c.caseId = a.caseId
             WHERE (:results  IS NULL OR a.result IN :results)
               AND (:channels IS NULL OR a.channel IN :channels)
               AND (:buckets  IS NULL OR c.currentBucket IN :buckets)
               AND (:agentIds IS NULL OR c.assignedAgentId IN :agentIds)
               AND a.attemptedAt BETWEEN :from AND :to
            """)
    Page<ContactQueueRow> searchContactAttempts(@Param("results") Collection<ContactResult> results,
                                                @Param("channels") Collection<ContactChannel> channels,
                                                @Param("buckets") Collection<DelinquencyBucket> buckets,
                                                @Param("agentIds") Collection<String> agentIds,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to,
                                                Pageable pageable);
}
