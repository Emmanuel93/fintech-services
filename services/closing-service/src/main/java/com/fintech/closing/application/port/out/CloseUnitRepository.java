package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.CloseUnit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CloseUnitRepository {

    /**
     * Candidatas a trabajar. Es un {@code SELECT} normal — <b>sin {@code FOR UPDATE}, sin
     * {@code SKIP LOCKED}</b>: la exclusión la da el candado de Redis y el CAS optimista.
     *
     * @param offset desplazamiento por pod, para que las réplicas no compitan siempre por las
     *               mismas filas. La colisión deja de ser la norma y pasa a ser el caso raro.
     */
    List<CloseUnit> findPending(UUID runId, int offset, int limit);

    /**
     * CAS optimista: pasa a {@code CLAIMED} <b>sólo si</b> sigue {@code PENDING}.
     *
     * @return 1 si se tomó, 0 si otro pod se adelantó. Cero es un resultado normal del reparto.
     */
    int claim(UUID unitId, String owner, long fencingToken, Instant leaseExpiresAt);

    /** Unidades cuyo arrendamiento venció. El pod que las tenía murió sin liberarlas. */
    List<CloseUnit> findExpiredLeases(Instant now, int limit);

    int countByStatus(UUID runId, String status);
    Optional<CloseUnit> findById(UUID unitId);
    CloseUnit save(CloseUnit unit);
    int insertBatch(List<CloseUnit> units);
}
