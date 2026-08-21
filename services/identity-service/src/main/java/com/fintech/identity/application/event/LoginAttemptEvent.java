package com.fintech.identity.application.event;

import com.fintech.identity.application.DeviceTrackingResult;
import com.fintech.identity.application.LoginCommand;
import com.fintech.identity.application.MfaVerifyCommand;
import com.fintech.identity.domain.CredentialType;
import com.fintech.identity.domain.IdentityCredential;
import com.fintech.identity.domain.event.GeoLocation;
import com.fintech.identity.domain.event.LoginOutcome;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento emitido a Kafka en cada intento de inicio de sesión (exitoso o fallido).
 * Contiene todos los campos necesarios para cumplimiento regulatorio:
 * CNBV, CONDUSEF, PCI-DSS, SOC2, GDPR.
 */
public record LoginAttemptEvent(

        /** Nombre del evento — permite a los consumidores enrutar sin depender del topic. */
        String eventName,

        /** Identificador único del evento para idempotencia y trazabilidad. */
        UUID eventId,

        /** Versión del esquema del evento para evolución sin romper consumidores. */
        String eventVersion,

        /** Momento exacto del intento en UTC. */
        Instant occurredAt,

        /** Nombre de usuario utilizado en el intento. */
        String username,

        /** Identificador interno del party; null si el username no existe. */
        UUID partyId,

        /** Resultado del intento de autenticación. */
        LoginOutcome outcome,

        // ── Contexto de red ───────────────────────────────────────────────

        /** IP del cliente; considera cabeceras X-Forwarded-For para proxies/balanceadores. */
        String ipAddress,

        /** Datos de geolocalización derivados de la IP. */
        GeoLocation geoLocation,

        /** Cadena User-Agent del cliente (navegador, app móvil, SDK). */
        String userAgent,

        /** X-Request-ID para correlación entre servicios. */
        String requestId,

        // ── Dispositivo ───────────────────────────────────────────────────

        /** ID opaco enviado por el cliente para identificar el dispositivo. */
        String deviceId,

        /** UUID del registro en identity.devices; null si no se proporcionó deviceId. */
        UUID deviceRegistryId,

        /**
         * true si este dispositivo inicia sesión por primera vez para este party.
         * Permite a auditoría detectar y alertar accesos desde dispositivos desconocidos.
         */
        boolean isNewDevice,

        // ── Autenticación ─────────────────────────────────────────────────

        /** Tipo de credencial utilizada: NIP o PASSWORD. null si el username no existe. */
        CredentialType credentialType,

        /** true si el inicio de sesión completó el flujo MFA (2FA TOTP). */
        boolean mfaUsed,

        // ── Sesión emitida (solo en outcomes exitosos) ────────────────────

        /** JTI (JWT ID) del access token emitido; null si el intento falló. */
        String sessionTokenId,

        /** Cuando expira la sesión emitida; null si el intento falló. */
        Instant sessionExpiresAt,

        // ── Fallo (solo en outcomes fallidos) ─────────────────────────────

        /** Descripción legible del motivo de fallo; null si fue exitoso. */
        String failureReason,

        /** Conteo de intentos fallidos acumulados después de este intento. */
        int failedAttemptCount,

        /** Momento hasta el que la cuenta quedará bloqueada; null si no está bloqueada. */
        Instant accountLockedUntil

) {
    static final String EVENT_NAME = "identity.login-attempted";
    static final String VERSION    = "1.0";

    // ── Factorías por outcome ─────────────────────────────────────────────

    public static LoginAttemptEvent credentialNotFound(LoginCommand cmd, GeoLocation geo) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                cmd.username(), null,
                LoginOutcome.CREDENTIAL_NOT_FOUND,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                cmd.deviceId(), null, false,
                null, false,
                null, null,
                "Username no registrado", 0, null);
    }

    public static LoginAttemptEvent accountAlreadyLocked(LoginCommand cmd,
                                                          IdentityCredential credential,
                                                          GeoLocation geo,
                                                          DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                cmd.username(), credential.getPartyId(),
                LoginOutcome.ACCOUNT_ALREADY_LOCKED,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                cmd.deviceId(), device.deviceRegistryId(), device.isNewDevice(),
                credential.getCredentialType(), false,
                null, null,
                "Cuenta bloqueada hasta " + credential.getLockedUntil(),
                credential.getFailedAttempts(),
                credential.getLockedUntil());
    }

    public static LoginAttemptEvent failedCredentials(LoginCommand cmd,
                                                       IdentityCredential credential,
                                                       GeoLocation geo,
                                                       DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                cmd.username(), credential.getPartyId(),
                LoginOutcome.FAILED_CREDENTIALS,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                cmd.deviceId(), device.deviceRegistryId(), device.isNewDevice(),
                credential.getCredentialType(), false,
                null, null,
                "Credenciales incorrectas",
                credential.getFailedAttempts(), null);
    }

    public static LoginAttemptEvent accountLocked(LoginCommand cmd,
                                                   IdentityCredential credential,
                                                   GeoLocation geo,
                                                   DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                cmd.username(), credential.getPartyId(),
                LoginOutcome.ACCOUNT_LOCKED,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                cmd.deviceId(), device.deviceRegistryId(), device.isNewDevice(),
                credential.getCredentialType(), false,
                null, null,
                "Cuenta bloqueada por exceder intentos fallidos",
                credential.getFailedAttempts(),
                credential.getLockedUntil());
    }

    public static LoginAttemptEvent mfaRequired(LoginCommand cmd,
                                                 IdentityCredential credential,
                                                 GeoLocation geo,
                                                 DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                cmd.username(), credential.getPartyId(),
                LoginOutcome.SUCCESS_MFA_REQUIRED,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                cmd.deviceId(), device.deviceRegistryId(), device.isNewDevice(),
                credential.getCredentialType(), false,
                null, null,
                null, 0, null);
    }

    public static LoginAttemptEvent success(LoginCommand cmd,
                                             IdentityCredential credential,
                                             GeoLocation geo,
                                             String jti,
                                             Instant sessionExpiresAt,
                                             DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                cmd.username(), credential.getPartyId(),
                LoginOutcome.SUCCESS,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                cmd.deviceId(), device.deviceRegistryId(), device.isNewDevice(),
                credential.getCredentialType(), false,
                jti, sessionExpiresAt,
                null, 0, null);
    }

    public static LoginAttemptEvent failedMfa(MfaVerifyCommand cmd,
                                               UUID partyId,
                                               String username,
                                               GeoLocation geo,
                                               DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                username, partyId,
                LoginOutcome.FAILED_MFA_CODE,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                null, device.deviceRegistryId(), device.isNewDevice(),
                null, false,
                null, null,
                "Código TOTP inválido", 0, null);
    }

    public static LoginAttemptEvent successWithMfa(MfaVerifyCommand cmd,
                                                    UUID partyId,
                                                    String username,
                                                    GeoLocation geo,
                                                    String jti,
                                                    Instant sessionExpiresAt,
                                                    DeviceTrackingResult device) {
        return new LoginAttemptEvent(
                EVENT_NAME, UUID.randomUUID(), VERSION, Instant.now(),
                username, partyId,
                LoginOutcome.SUCCESS_MFA_VERIFIED,
                cmd.ipAddress(), geo, cmd.userAgent(), cmd.requestId(),
                null, device.deviceRegistryId(), device.isNewDevice(),
                null, true,
                jti, sessionExpiresAt,
                null, 0, null);
    }
}
