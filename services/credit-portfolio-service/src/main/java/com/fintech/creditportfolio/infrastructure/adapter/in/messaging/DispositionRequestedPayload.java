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
        /**
         * Se conserva en el payload por compatibilidad con quien aún lo publica, pero
         * <b>cartera lo ignora</b>: el tipo lo decide el producto (BK-13). Dejarlo declarado y no
         * usarlo es deliberado — así un emisor viejo no rompe la deserialización mientras deja de
         * mandarlo, y el campo muerto es visible en vez de silencioso.
         */
        String dispositionTypeIgnorado,
        UUID beneficiaryPartyId,
        String payeeAccount,
        /** Plazo de la colocación: cada disposición amortiza por su cuenta. */
        Integer termPeriods,
        Instant occurredOn
) {}
