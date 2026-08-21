package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Selección de producto de una persona ya onboardeada (ADR-001).
 * `requestedAmount`/`requestedTerm` son opcionales (null para revolventes).
 * `prospectId` ya no es obligatorio a nivel de request — el controller usa el
 * del JWT (X-User-Id), autoritativo, no lo que mande el cliente (AppSession en
 * el cliente solo se puebla en el registro dentro de la misma sesión de la
 * app; un login normal a una cuenta ya existente lo deja vacío).
 */
public record CreateApplicationRequest(

        String prospectId,

        @NotBlank
        String productType,

        @Positive
        Double requestedAmount,

        @Positive
        Integer requestedTerm,

        /**
         * Distribuidor que respalda la solicitud: su código (p. ej. {@code DIST0001}) o el UUID de
         * su party. Opcional — sin él es crédito directo. Un código inexistente rechaza la
         * solicitud en origination (CM-07), en vez de dejar un crédito sin a quién comisionar.
         */
        String promoterCode
) {}
