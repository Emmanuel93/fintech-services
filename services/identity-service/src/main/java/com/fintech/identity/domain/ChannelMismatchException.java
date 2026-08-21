package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

/**
 * Un token emitido para un canal se presentó en el flujo de otro. Cierra la puerta a que un refresh
 * de la app móvil se convierta en una sesión de backoffice (o al revés) por usar el endpoint
 * equivocado.
 */
public class ChannelMismatchException extends DomainException {

    public ChannelMismatchException(Channel expected, Channel actual) {
        super("AUTH_CHANNEL_MISMATCH",
              "Token belongs to channel " + actual + " but " + expected + " was required");
    }
}
