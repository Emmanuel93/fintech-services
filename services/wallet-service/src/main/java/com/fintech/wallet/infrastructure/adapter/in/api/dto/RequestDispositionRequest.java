package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record RequestDispositionRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        /**
         * <b>Ya no se usa, y su {@code @NotBlank} bloqueaba la petición.</b>
         *
         * <p>El tipo de disposición lo decide el <b>producto</b> (BK-13): cartera dejó de leerlo
         * porque mandarlo permitía acreditar a la distribuidora un dinero que era de la
         * beneficiaria. Se conserva el campo —opcional— para que un cliente que aún lo mande no
         * reciba un 400, y se ignora.
         */
        String dispositionTypeIgnorado,
        UUID beneficiaryPartyId,   // required for DISTRIBUTOR_LINE
        String payeeAccount,
        /** A cuántos períodos se amortiza esta colocación. Nulo cae al plazo por defecto. */
        Integer termPeriods
) {}
