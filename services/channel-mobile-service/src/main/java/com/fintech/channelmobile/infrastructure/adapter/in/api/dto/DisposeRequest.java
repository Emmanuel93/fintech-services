package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.Positive;

/**
 * Disposición de crédito. Para líneas propias (revolvente/personal) basta {@code amount}
 * (dispositionType=SELF_USE). Para líneas de distribuidor (B2B2C), el crédito fluye a un
 * tercero: dispositionType=THIRD_PARTY_CREDIT + beneficiaryPartyId obligatorio.
 */
public record DisposeRequest(
        @Positive double amount,
        String dispositionType,
        String beneficiaryPartyId,
        /**
         * A cuántos meses se coloca. Una línea revolvente no tiene plazo; lo tiene cada
         * colocación, igual que una compra a meses en una tarjeta. Nulo cae al plazo por defecto
         * del producto.
         */
        Integer termPeriods
) {}
