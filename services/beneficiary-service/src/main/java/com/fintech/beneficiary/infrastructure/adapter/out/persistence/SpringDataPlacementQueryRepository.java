package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * La bandeja del backoffice en una sola consulta.
 *
 * <p>{@code updatedAt <= :stalledBefore} es el filtro de SLA: «lo que no se mueve desde hace N
 * días». Se compara contra {@code updatedAt} y no contra {@code createdAt} porque lo que importa
 * en una bandeja no es cuándo nació el caso sino cuánto lleva atorado — una colocación creada hace
 * un mes que ayer pasó a {@code BUREAU_READY} no está atorada, está esperando decisión desde ayer.
 */
interface SpringDataPlacementQueryRepository extends JpaRepository<Placement, UUID> {

    @Query("""
            SELECT p FROM Placement p
             WHERE (:distributorPartyIds IS NULL OR p.distributorPartyId IN :distributorPartyIds)
               AND (:statuses IS NULL OR p.status IN :statuses)
               AND (:identityDecisions IS NULL OR p.identityDecision IN :identityDecisions)
               AND p.createdAt BETWEEN :from AND :to
               AND p.updatedAt <= :stalledBefore
            """)
    Page<Placement> search(@Param("distributorPartyIds") Collection<UUID> distributorPartyIds,
                           @Param("statuses") Collection<PlacementStatus> statuses,
                           @Param("identityDecisions") Collection<IdentityDecision> identityDecisions,
                           @Param("from") Instant from,
                           @Param("to") Instant to,
                           @Param("stalledBefore") Instant stalledBefore,
                           Pageable pageable);
}
