package com.fintech.party.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/** Outbound {@code party.fiscal-profile-updated} — consumido por Facturación (receptor del CFDI). */
public record FiscalProfileUpdatedPayload(
        UUID    partyId,
        /**
         * El id con el que el resto del sistema conoce a este cliente.
         *
         * <p>Cartera, contabilidad y facturación llevan el <b>prospectId</b> en {@code obligorPartyId}:
         * el crédito nace de la solicitud y arrastra el id del prospecto, no el del party que se creó
         * después. El BFF ya lo sabe y resuelve nombres probando {@code prospectId} primero.
         *
         * <p>Facturación no lo sabía, así que buscaba el perfil fiscal por {@code partyId}, no
         * encontraba nada y timbraba todo a «público en general» — sin error, con folio, y sin forma
         * de notarlo salvo mirando que todos los CFDI tienen el mismo RFC.
         */
        UUID    prospectId,
        String  partyType,
        String  rfc,
        String  taxName,
        String  taxRegime,
        String  taxZipCode,
        String  cfdiUse,
        Instant updatedAt
) {}
