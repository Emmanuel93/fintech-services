package com.fintech.salesorg.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbound {@code credit-portfolio.credit-account-activated} — el desembolso.
 *
 * <p>Es el momento en que nace un activo que alguien tiene que gestionar, y por eso es aquí y no en
 * la solicitud donde se reparte la cartera: una solicitud rechazada o no formalizada no genera nada
 * que administrar, y asignarla repartiría carteras que nunca van a existir.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String contractNumber,
        /** La sucursal que decidió la geografía. Si viene vacía, no hay a qué rama atribuir. */
        String originUnitCode,
        Instant occurredOn
) {}
