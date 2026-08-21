package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.PlacementQueryRepository;
import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Normaliza los filtros antes de que lleguen a la consulta.
 *
 * <p>Dos cosas, y las dos son cicatrices:
 *
 * <ul>
 *   <li><b>Colección vacía → {@code null}.</b> {@code (:ids IS NULL OR x IN :ids)} funciona para
 *       UUID y enums, pero un {@code IN ()} vacío no es SQL válido. El controlador puede recibir
 *       {@code ?status=} sin valores y eso significa «todos», no «ninguno».</li>
 *   <li><b>Las fechas nunca van nulas.</b> {@code (:fecha IS NULL OR ...)} revienta en Postgres con
 *       parámetros temporales porque no puede inferir el tipo del {@code NULL}. Se pasan límites
 *       por defecto —{@code EPOCH} y {@code now}— que son la misma pregunta sin el {@code NULL}.</li>
 * </ul>
 */
@Repository
class JpaPlacementQueryAdapter implements PlacementQueryRepository {

    private final SpringDataPlacementQueryRepository jpa;

    JpaPlacementQueryAdapter(SpringDataPlacementQueryRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Page<Placement> search(Collection<UUID> distributorPartyIds,
                                  Collection<PlacementStatus> statuses,
                                  Collection<IdentityDecision> identityDecisions,
                                  Instant from, Instant to, Instant stalledBefore,
                                  Pageable pageable) {
        return jpa.search(
                emptyToNull(distributorPartyIds),
                emptyToNull(statuses),
                emptyToNull(identityDecisions),
                from  != null ? from  : Instant.EPOCH,
                to    != null ? to    : Instant.now(),
                stalledBefore != null ? stalledBefore : Instant.now(),
                pageable);
    }

    private static <T> List<T> emptyToNull(Collection<T> values) {
        return values == null || values.isEmpty() ? null : List.copyOf(values);
    }
}
