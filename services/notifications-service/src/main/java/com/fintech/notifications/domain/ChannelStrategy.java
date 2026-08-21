package com.fintech.notifications.domain;

/**
 * SIMULTANEOUS: se envía a todos los canales disponibles a la vez (picos emocionales, NT-08).
 * SEQUENTIAL_FALLBACK: se intenta el canal primario y se cae al siguiente solo si no está disponible.
 */
public enum ChannelStrategy {
    SIMULTANEOUS, SEQUENTIAL_FALLBACK
}
