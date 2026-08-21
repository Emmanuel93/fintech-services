package com.fintech.stp.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Alta o rotación de material criptográfico.
 *
 * <p>KY-04: el {@code materialBase64} se cifra dentro de la misma llamada y no se escribe a disco
 * ni a log. La respuesta devuelve metadatos, nunca el material.
 *
 * @param purpose        {@code SIGNING} (PKCS#8 privada) o {@code VERIFICATION} (SPKI pública de STP)
 * @param materialBase64 el material, en Base64
 */
public record RegisterKeyRequest(
        @NotBlank String alias,
        @NotBlank String purpose,
        @NotBlank String materialBase64,
        @NotNull Instant validFrom,
        @NotNull Instant validTo
) {}
