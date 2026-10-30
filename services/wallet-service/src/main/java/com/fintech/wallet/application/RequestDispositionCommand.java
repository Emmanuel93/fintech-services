package com.fintech.wallet.application;

import java.math.BigDecimal;
import java.util.UUID;

public record RequestDispositionCommand(
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType,
        UUID beneficiaryPartyId,   // required for DISTRIBUTOR_LINE (DO-03)
        String payeeAccount,       // target account for SPEI transfer
        /** Titular de {@code payeeAccount}: viaja con la CLABE porque son el mismo destino. */
        String payeeName,
        String payeeTaxId,
        /** Plazo de la colocación. Una revolvente no tiene plazo; cada disposición sí. */
        Integer termPeriods
) {}
