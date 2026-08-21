package com.fintech.notifications.domain;

import com.fintech.shared.exception.DomainException;

import java.util.UUID;

/**
 * Se pidió notificar a una entidad que nadie registró.
 *
 * <p>Es un error del emisor y no un envío silenciosamente perdido: quien manda el aviso es quien
 * conoce a la entidad, así que si no está inscrita, lo que falta es el alta — y enterarse al
 * enviar es mejor que no enterarse nunca.
 */
public class UnknownRecipientException extends DomainException {
    public UnknownRecipientException(String recipientType, UUID recipientId) {
        super("NOTIFICATIONS_UNKNOWN_RECIPIENT",
                "No hay destinatario registrado " + recipientType + "/" + recipientId);
    }
}
