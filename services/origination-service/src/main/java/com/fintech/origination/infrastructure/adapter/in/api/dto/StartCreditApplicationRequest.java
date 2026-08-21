package com.fintech.origination.infrastructure.adapter.in.api.dto;

import com.fintech.origination.domain.ProductType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to start a credit application. The prospect must already be onboarded.
 * {@code requestedAmount} / {@code requestedTerm} are optional (null for revolving
 * products without an upfront amount).
 */
public record StartCreditApplicationRequest(

        @NotNull(message = "prospectId is required")
        UUID prospectId,

        @NotNull(message = "productType is required")
        ProductType productType,

        @Positive(message = "requestedAmount must be positive")
        BigDecimal requestedAmount,

        @Positive(message = "requestedTerm must be positive")
        Integer requestedTerm,

        /**
         * Distribuidor que respalda la solicitud. Opcional: sin él es crédito directo.
         *
         * <p>Acepta el código humano (p. ej. {@code DIST0001}), que se resuelve contra sales-org, o
         * directamente el UUID del party del distribuidor. Un código que no corresponde a ningún
         * distribuidor <b>rechaza</b> la solicitud (CM-07): es preferible eso a activar un crédito
         * que después no sabrá a quién pagarle la comisión.
         *
         * <p>Hasta ahora este campo no existía y el controller mandaba {@code null} fijo, con lo
         * que un crédito originado por esta ruta —la que usan las siembras y cualquier integración
         * directa— nacía sin atribución: sin comisión para el distribuidor y ausente del tablero
         * comercial, que se arma cruzando el subárbol contra los créditos por promotor.
         */
        String promoterCode
) {}
