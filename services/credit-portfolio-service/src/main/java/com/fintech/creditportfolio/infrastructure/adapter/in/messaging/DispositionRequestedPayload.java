package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Inbound from wallet (topic {@code wallet.disposition-requested}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispositionRequestedPayload(
        UUID dispositionRequestId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        String dispositionType,
        UUID beneficiaryPartyId,
        String payeeAccount,
        /** Plazo de la colocación: cada disposición amortiza por su cuenta. */
        Integer termPeriods,
        Instant occurredOn
) {}
