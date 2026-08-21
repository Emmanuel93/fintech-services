package com.fintech.identity.application;

/**
 * Comando de verificación TOTP; completa el flujo de inicio de sesión con 2FA.
 * Incluye metadata de red para el evento de auditoría regulatorio.
 */
public record MfaVerifyCommand(

        String mfaToken,
        String totpCode,

        /** IP del cliente al momento de verificar el TOTP. */
        String ipAddress,

        /** User-Agent del cliente. */
        String userAgent,

        /** X-Request-ID para correlación distribuida. */
        String requestId
) {}
