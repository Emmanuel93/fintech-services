package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Entrante {@code party.executive-assigned} — un cliente pasó a ser de un ejecutivo.
 *
 * <p>Se redeclara aquí en vez de importarlo de party: este servicio no depende del código de
 * ningún dominio, y por eso puede consumir sus hechos sin atarse a sus clases. Lo que sí queda
 * atado es el <b>esquema</b>, y para eso está la prueba de contrato del par.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutiveAssignedPayload(
        UUID partyId,
        UUID executiveId,
        String executiveName,
        String clientName
) {}
