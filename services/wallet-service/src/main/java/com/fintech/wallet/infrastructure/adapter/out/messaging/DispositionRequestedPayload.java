package com.fintech.wallet.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record DispositionRequestedPayload(
        UUID dispositionRequestId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType,
        UUID beneficiaryPartyId,
        String payeeAccount,
        /** Titular de la CLABE de destino. Cartera lo exige en una colocación (credit-portfolio). */
        String payeeName,
        String payeeTaxId,
        Integer termPeriods,
        Instant occurredOn
) {}
