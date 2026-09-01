package com.fintech.origination.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SignContractRequest(
        @NotBlank @Pattern(regexp = "\\d{18}", message = "CLABE must be 18 digits") String clabeAccount,
        @NotBlank String signatureProof,
        String documentRef,
        /**
         * Días de espera antes del primer pago (BNPL). Opcional: sin él, se paga desde el período
         * siguiente, como siempre.
         *
         * <p>No se valida contra el tope aquí. Quien conoce el tope es el producto, y cartera lo
         * aplica recortando lo pedido — negar la firma entera por pedir de más convertiría un
         * límite en un obstáculo justo en el último paso del alta.
         */
        @jakarta.validation.constraints.PositiveOrZero Integer bnplDeferralDays
) {}
