package com.fintech.origination.infrastructure.adapter.out.persistence;

import com.fintech.origination.domain.DocumentReviewStatus;
import com.fintech.origination.domain.ProspectDocumentType;
import com.fintech.origination.domain.VerificationSource;

import java.time.Instant;
import java.util.UUID;

/**
 * Lo que hay en el expediente, sin abrirlo — y en qué estado de dictamen está.
 *
 * <p>El estado viaja en el listado y no en un endpoint aparte porque la pregunta del analista es
 * una sola: «¿qué me falta por revisar?». Partirla en dos llamadas le haría reconstruir a mano lo
 * que la consulta ya sabe.
 */
public record DocumentFileSummary(
        UUID fileId,
        ProspectDocumentType documentType,
        String fileName,
        String contentType,
        long sizeBytes,
        Instant uploadedAt,
        DocumentReviewStatus reviewStatus,
        VerificationSource verificationSource,
        String reviewedBy,
        Instant reviewedAt,
        String rejectionReason) {}
