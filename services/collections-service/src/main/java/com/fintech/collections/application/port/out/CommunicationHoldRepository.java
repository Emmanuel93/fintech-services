package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.CommunicationHold;
import com.fintech.collections.domain.HoldReason;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunicationHoldRepository {

    CommunicationHold save(CommunicationHold hold);

    /** El freno vivo de un caso por un motivo dado, si lo hay. */
    Optional<CommunicationHold> findActive(UUID caseId, HoldReason reason, Instant moment);

    /** Todos los frenos vivos de un caso. Vale el más lejano. */
    List<CommunicationHold> findAllActive(UUID caseId, Instant moment);

    /** Historial completo, para contestar por qué no se le escribió a alguien en un periodo. */
    List<CommunicationHold> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    /**
     * Los casos que tienen algún freno vivo, para descartarlos de la corrida de la cadencia en una
     * consulta en vez de una por caso.
     */
    List<UUID> findCaseIdsWithActiveHold(Instant moment);
}
