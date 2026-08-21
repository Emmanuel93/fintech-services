package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.CommunicationHold;

import java.time.Instant;
import java.util.UUID;

/**
 * Un periodo de silencio, con {@code active} ya resuelto.
 *
 * <p>Se calcula en el servidor porque depende de comparar {@code heldUntil} con «ahora», y el ahora
 * del navegador no es el mismo: un reloj adelantado mostraría como vencido un freno que el job
 * sigue respetando, y la pantalla contradiría al sistema.
 */
public record CommunicationHoldResponse(
        UUID holdId,
        UUID caseId,
        String reason,
        Instant heldUntil,
        UUID sourceId,
        boolean active,
        Instant releasedAt,
        String releasedBy,
        String releaseNote,
        Instant createdAt
) {
    public static CommunicationHoldResponse from(CommunicationHold h, Instant moment) {
        return new CommunicationHoldResponse(
                h.getHoldId(), h.getCaseId(), h.getReason().name(), h.getHeldUntil(), h.getSourceId(),
                h.isActiveAt(moment), h.getReleasedAt(), h.getReleasedBy(), h.getReleaseNote(),
                h.getCreatedAt());
    }
}
