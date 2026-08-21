package com.fintech.notifications.domain;

import com.fintech.shared.exception.DomainException;

/** El destinatario que se intenta registrar no sirve para notificar. */
public class InvalidRecipientException extends DomainException {
    public InvalidRecipientException(String detail) {
        super("NOTIFICATIONS_INVALID_RECIPIENT", detail);
    }
}
