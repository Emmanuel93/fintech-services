package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.ContactQueueRow;
import com.fintech.collections.application.PromiseQueueRow;
import com.fintech.collections.application.port.out.CollectionsQueueRepository;
import com.fintech.collections.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

/**
 * Normaliza los filtros antes de la consulta.
 *
 * <p>Colección vacía → {@code null}, porque {@code IN ()} no es SQL válido y un filtro sin valores
 * significa «todos». Fechas siempre con límites por defecto, porque comparar un parámetro temporal
 * contra {@code NULL} revienta en Postgres.
 */
@Repository
class JpaCollectionsQueueAdapter implements CollectionsQueueRepository {

    /** Los límites «sin filtro». No son mágicos: son los extremos del dominio de la columna. */
    private static final LocalDate DATE_FLOOR = LocalDate.of(1970, 1, 1);
    private static final LocalDate DATE_CEIL  = LocalDate.of(9999, 12, 31);

    private final SpringDataCollectionsQueueRepository jpa;

    JpaCollectionsQueueAdapter(SpringDataCollectionsQueueRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Page<PromiseQueueRow> searchPromises(Collection<PromiseStatus> statuses,
                                                Collection<DelinquencyBucket> buckets,
                                                Collection<String> agentIds,
                                                LocalDate dueFrom, LocalDate dueTo,
                                                Pageable pageable) {
        return jpa.searchPromises(
                emptyToNull(statuses), emptyToNull(buckets), emptyToNull(agentIds),
                dueFrom != null ? dueFrom : DATE_FLOOR,
                dueTo   != null ? dueTo   : DATE_CEIL,
                startOfToday(),
                pageable);
    }

    @Override
    public Page<ContactQueueRow> searchContactAttempts(Collection<ContactResult> results,
                                                       Collection<ContactChannel> channels,
                                                       Collection<DelinquencyBucket> buckets,
                                                       Collection<String> agentIds,
                                                       Instant from, Instant to,
                                                       Pageable pageable) {
        return jpa.searchContactAttempts(
                emptyToNull(results), emptyToNull(channels), emptyToNull(buckets), emptyToNull(agentIds),
                from != null ? from : Instant.EPOCH,
                to   != null ? to   : Instant.now(),
                pageable);
    }

    /**
     * El arranque del día para contar intentos.
     *
     * <p>En hora de México y no en UTC: el tope de intentos diarios es una regla de trato al
     * cliente, y su día es el de la persona a la que se le llama. Con UTC, el contador se
     * reiniciaría a las 6 de la tarde y permitiría tres llamadas más esa misma noche.
     */
    private static Instant startOfToday() {
        return LocalDate.now(ZoneId.of("America/Mexico_City"))
                .atStartOfDay(ZoneId.of("America/Mexico_City")).toInstant();
    }

    private static <T> List<T> emptyToNull(Collection<T> values) {
        return values == null || values.isEmpty() ? null : List.copyOf(values);
    }
}
