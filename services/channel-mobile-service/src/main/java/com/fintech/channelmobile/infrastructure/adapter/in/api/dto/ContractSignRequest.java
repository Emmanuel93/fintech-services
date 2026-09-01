package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Firma del contrato: CLABE de desembolso (18 dígitos) + prueba de firma (OTP/biométrica). */
public record ContractSignRequest(
        @NotBlank @Pattern(regexp = "\\d{18}", message = "La CLABE debe tener 18 dígitos") String clabeAccount,
        @NotBlank String signatureProof,
        String documentRef,
        /**
         * Días que el cliente pide esperar antes de su primer pago (BNPL). Opcional.
         *
         * <p>Sin él se paga desde el período siguiente, como siempre. No se valida contra el tope
         * aquí ni en originación: quien conoce el tope es el producto, y cartera recorta lo pedido.
         * Negar la firma por pedir de más convertiría un límite en un obstáculo en el último paso.
         */
        @jakarta.validation.constraints.PositiveOrZero Integer bnplDeferralDays
) {}
