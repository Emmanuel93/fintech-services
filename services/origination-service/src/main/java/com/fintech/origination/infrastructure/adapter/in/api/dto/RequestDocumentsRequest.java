package com.fintech.origination.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Petición de documentos adicionales. {@code note} explica qué se pide (viaja al sujeto en la
 * notificación); {@code decidedBy} es el empleado que lo solicita.
 */
public record RequestDocumentsRequest(
        @NotBlank String decidedBy,
        @NotBlank String note) {}
