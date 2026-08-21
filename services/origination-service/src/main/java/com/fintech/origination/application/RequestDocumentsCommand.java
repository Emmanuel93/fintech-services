package com.fintech.origination.application;

import java.util.UUID;

/**
 * El analista/comité pide documentos adicionales para una solicitud en revisión.
 *
 * @param applicationId la solicitud
 * @param requestedBy   empleado que lo solicita (atribución)
 * @param note          qué documentos se piden (viaja al sujeto en la notificación)
 * @param correlationId traza
 */
public record RequestDocumentsCommand(
        UUID applicationId,
        String requestedBy,
        String note,
        String correlationId) {}
