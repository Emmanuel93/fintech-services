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
        /**
         * A nombre de quién está {@code payeeAccount}. Viaja junto a la CLABE porque son un mismo
         * dato: el SPEI de una colocación salía con la CLABE de la beneficiaria y el nombre y RFC
         * de la distribuidora, porque cartera sólo conocía los de la cuenta.
         */
        String payeeName,
        /** RFC o CURP del titular de {@code payeeAccount}. Opcional: SPEI lo admite vacío. */
        String payeeTaxId,
        /** Plazo de la colocación: cada disposición amortiza por su cuenta. */
        Integer termPeriods,
        Instant occurredOn
) {}
