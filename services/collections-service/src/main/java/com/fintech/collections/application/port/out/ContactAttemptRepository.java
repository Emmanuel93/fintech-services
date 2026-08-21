package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.ContactAttempt;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ContactAttemptRepository {

    List<ContactAttempt> findByCaseId(UUID caseId);

    /**
     * CT-03: intentos <b>de agente</b> del día, que son los que cuentan contra el tope.
     *
     * <p>Los automáticos quedan fuera a propósito. Si contaran, la cadencia de la mañana agotaría
     * el cupo antes de que el gestor abriera el caso, y el límite pensado para proteger al cliente
     * acabaría impidiendo la única llamada que podía resolverle el problema.
     */
    long countManualByCaseIdAndAttemptedAtAfter(UUID caseId, Instant since);

    /** Todos los del día, automáticos incluidos. Para el reporte de gestión, no para el tope. */
    long countByCaseIdAndAttemptedAtAfter(UUID caseId, Instant since);

    ContactAttempt save(ContactAttempt attempt);
}
