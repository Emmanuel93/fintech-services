package com.fintech.identity.application;

/**
 * Comando de inicio de sesión; agrupa credenciales y metadata de red
 * necesaria para el evento de auditoría.
 */
public record LoginCommand(

        String username,
        String password,

        /** IP del cliente; considera X-Forwarded-For. */
        String ipAddress,

        /** User-Agent del cliente. */
        String userAgent,

        /** Identificador de dispositivo opcional. */
        String deviceId,

        /** X-Request-ID para correlación distribuida. */
        String requestId
) {}
