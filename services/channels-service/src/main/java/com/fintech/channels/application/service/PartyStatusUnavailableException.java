package com.fintech.channels.application.service;

import java.util.UUID;

/**
 * No se pudo comprobar si la parte está en lista negra, así que el intent no avanza.
 *
 * <p>Es distinto de «está en lista negra»: ahí se abandona el intent con motivo, porque se sabe.
 * Aquí <b>no se sabe</b>, y no saber no es aprobar. Se rechaza el enrutamiento —que es reintentable
 * en cuanto party conteste— en vez de abandonar, que afirmaría algo que no se comprobó.
 */
public class PartyStatusUnavailableException extends RuntimeException {

    private final UUID partyId;

    public PartyStatusUnavailableException(UUID partyId, Throwable cause) {
        super("No se pudo comprobar el estatus de la parte " + partyId
              + "; el intent no avanza sin esa comprobación", cause);
        this.partyId = partyId;
    }

    public UUID partyId() { return partyId; }
}
