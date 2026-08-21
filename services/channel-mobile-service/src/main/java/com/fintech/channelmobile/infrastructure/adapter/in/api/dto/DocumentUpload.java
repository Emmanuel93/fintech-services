package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Un documento del expediente tal como lo manda la app.
 *
 * <p>El contenido va en base64 dentro del JSON y no como multipart porque el alta entera es una
 * conversación JSON —OTP, datos, consentimientos— y meter un segundo formato en el borde para
 * cuatro fotos no paga su costo.
 *
 * <p>{@code documentType} es el vocabulario de origination ({@code INE_FRONT}, {@code INE_BACK},
 * {@code SELFIE}, {@code ADDRESS_PROOF}, {@code INCOME_PROOF}); la app traduce sus propias
 * etiquetas antes de mandarlas, porque el nombre que se le enseña a una persona y el que usa el
 * dominio no tienen por qué ser el mismo.
 */
public record DocumentUpload(
        @NotBlank String documentType,
        @NotBlank String fileName,
        @NotBlank String contentType,
        @NotBlank String contentBase64) {}
