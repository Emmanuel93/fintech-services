package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * La consulta transversal de colocaciones: la que cruza <b>todos</b> los distribuidores.
 *
 * <p>Es un puerto aparte de {@link PlacementRepository} a propósito. Aquél sirve a la app del
 * distribuidor, donde el {@code distributorPartyId} sale del token y jamás de un parámetro —
 * pedirlo en la ruta dejaría que cualquiera leyera la cartera de otro. La consola de operaciones
 * hace la pregunta contraria: ver todas las colocaciones y filtrar por distribuidora. Mezclar las
 * dos en un mismo repositorio invita a que un día alguien exponga la transversal por la ruta de la
 * app.
 *
 * <p>Resuelve la bandeja entera en <b>una</b> consulta paginada. El BFF no itera filas: enriquece
 * con {@code /batch} los nombres que necesita.
 */
public interface PlacementQueryRepository {

    /**
     * Bandeja de colocaciones. Todos los filtros son opcionales.
     *
     * @param distributorPartyIds vacío o nulo = todas las distribuidoras
     * @param statuses            vacío o nulo = todos los estados
     * @param identityDecisions   veredicto de identidad; vacío o nulo = todos. Es la pregunta real
     *                            de la mesa de KYC: «qué tengo pendiente por dictaminar»
     * @param from                límite inferior de creación; nunca nulo (se pasa {@code EPOCH})
     * @param to                  límite superior de creación; nunca nulo (se pasa {@code now})
     * @param stalledBefore       sólo las que no se mueven desde antes de este instante; nunca nulo
     */
    Page<Placement> search(Collection<UUID> distributorPartyIds,
                           Collection<PlacementStatus> statuses,
                           Collection<IdentityDecision> identityDecisions,
                           Instant from,
                           Instant to,
                           Instant stalledBefore,
                           Pageable pageable);
}
