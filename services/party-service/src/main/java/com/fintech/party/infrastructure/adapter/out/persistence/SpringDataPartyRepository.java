package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyStatus;
import com.fintech.party.domain.PartyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPartyRepository extends JpaRepository<Party, UUID> {
    Optional<Party> findByProspectId(UUID prospectId);
    boolean existsByProspectId(UUID prospectId);

    List<Party> findByPartyIdIn(Collection<UUID> ids);

    List<Party> findByProspectIdIn(Collection<UUID> prospectIds);

    /**
     * Listado del backoffice. Los filtros nulos no filtran: una sola consulta
     * cubre todas las combinaciones y la paginación la resuelve la base.
     *
     * <p>{@code q} llega ya en minúsculas y con comodines (lo arma el adapter).
     * No se le aplica {@code LOWER()} aquí a propósito: Postgres liga un
     * parámetro nulo sin tipo como {@code bytea} y {@code lower(bytea)} no existe
     * —la consulta reventaría con el filtro vacío—; comparar contra la columna en
     * minúsculas es además lo que permite usar el índice GIN trigram.
     */
    @Query("SELECT p FROM Party p "
         + "WHERE (:type IS NULL OR p.partyType = :type) "
         + "  AND (:status IS NULL OR p.status = :status) "
         + "  AND (:executiveId IS NULL OR p.assignedExecutiveId = :executiveId) "
         + "  AND (:q IS NULL OR LOWER(p.firstName) LIKE :q OR LOWER(p.lastName1) LIKE :q "
         + "       OR LOWER(p.lastName2) LIKE :q OR LOWER(p.curp) LIKE :q OR LOWER(p.rfc) LIKE :q)")
    Page<Party> search(@Param("q") String q,
                       @Param("type") PartyType type,
                       @Param("status") PartyStatus status,
                       @Param("executiveId") UUID executiveId,
                       Pageable pageable);
}
