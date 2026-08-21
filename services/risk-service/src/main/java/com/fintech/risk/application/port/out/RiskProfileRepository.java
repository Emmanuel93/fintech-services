package com.fintech.risk.application.port.out;

import com.fintech.risk.domain.Ifrs9Stage;
import com.fintech.risk.domain.RiskProfile;
import com.fintech.risk.domain.RiskProfileStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RiskProfileRepository {
    Optional<RiskProfile> findByCreditAccountId(UUID creditAccountId);
    List<RiskProfile> findByObligorPartyId(UUID obligorPartyId);
    /** PR-01: the nightly job recomputes every ACTIVE profile. */
    List<RiskProfile> findByStatus(RiskProfileStatus status);
    boolean existsByCreditAccountId(UUID creditAccountId);
    RiskProfile save(RiskProfile profile);

    /**
     * Listado paginado para el backoffice/finanzas. Todos los filtros opcionales;
     * {@code partyId} nulo deja de acotar por sujeto — es lo que convierte esto en
     * una bandeja por población en vez de una consulta atada a un party.
     */
    Page<RiskProfile> search(UUID obligorPartyId, String productType, Ifrs9Stage stage,
                             RiskProfileStatus status, Pageable pageable);

    /**
     * Hidratación por lote por id de cuenta: es lo que deja pintar la columna de
     * riesgo/etapa de una tabla de cartera sin una consulta por fila.
     */
    List<RiskProfile> findByCreditAccountIdIn(Collection<UUID> creditAccountIds);
}
