package com.fintech.beneficiary.domain;

import java.time.Instant;

/**
 * Qué hay en el expediente, sin los bytes.
 *
 * <p>La ficha de la mesa de KYC lista los documentos en cada apertura; el archivo se baja sólo
 * cuando alguien lo abre. Traer el contenido para pintar una lista costaría megabytes por fila.
 */
public record KycDocumentSummary(KycDocumentType documentType, String contentType,
                                 long sizeBytes, Instant uploadedAt) {}
