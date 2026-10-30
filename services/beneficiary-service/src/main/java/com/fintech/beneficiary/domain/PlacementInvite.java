package com.fintech.beneficiary.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * La liga: el secreto con el que la beneficiaria abre su KYC, y los contadores que la protegen.
 *
 * <p>Guarda el <b>hash</b> del token, nunca el token. La ventana de 7 días vive en la colocación
 * ({@code Placement.inviteExpiresAt}); aquí se copia al acuñar porque una liga rotada no puede
 * heredar más vida que la que la colocación le concede.
 *
 * <p>El OTP y su rate limit viven en la misma fila, y no en Redis como decía el plan (§5, §6.2).
 * Desviación consciente: el servicio todavía no tiene una sola línea de código sobre Redis, y lo
 * que aquí se cuenta —envíos, intentos fallidos, congelamientos— es evidencia de que la liga se
 * defendió. En Postgres queda auditable y se puede bloquear la fila para serializar a dos pestañas
 * que verifican a la vez; en Redis se evaporaría con su TTL.
 *
 * <p><b>Un solo uso para producir un expediente</b> (§3.4): se quema ({@code redeemed_at}) al
 * cerrar el KYC. Mientras tanto puede acuñar varias sesiones de 15 minutos, cada una detrás de un
 * OTP. Reanudar no es reusar.
 */
@Entity
@Table(schema = "beneficiary", name = "placement_invites")
public class PlacementInvite {

    @Id
    @Column(name = "invite_id", nullable = false, updatable = false)
    private UUID inviteId;

    @Column(name = "placement_id", nullable = false, updatable = false)
    private UUID placementId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "redeemed_at")
    private Instant redeemedAt;

    // ── El OTP vigente — nulo cuando no hay ninguno pendiente ────────────────────────────────

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "otp_hash", length = 64)
    private String otpHash;

    @Column(name = "otp_expires_at")
    private Instant otpExpiresAt;

    // ── Rate limit ───────────────────────────────────────────────────────────────────────────

    @Column(name = "otp_send_count", nullable = false)
    private int otpSendCount;

    @Column(name = "otp_window_started_at")
    private Instant otpWindowStartedAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "frozen_until")
    private Instant frozenUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PlacementInvite() {}

    /**
     * Acuña una liga para la colocación. Quien llama genera el token y se lo queda para
     * entregarlo; aquí sólo llega su hash.
     */
    public static PlacementInvite mint(UUID placementId, String tokenHash, Instant expiresAt, Instant now) {
        if (placementId == null || tokenHash == null || tokenHash.length() != 64 || expiresAt == null) {
            throw new PlacementValidationException("Una liga necesita colocación, hash de 64 y vigencia");
        }
        PlacementInvite i = new PlacementInvite();
        i.inviteId    = UUID.randomUUID();
        i.placementId = placementId;
        i.tokenHash   = tokenHash;
        i.expiresAt   = expiresAt;
        i.createdAt   = now;
        return i;
    }

    // ── Consultas ────────────────────────────────────────────────────────────────────────────

    /** Si la liga todavía puede usarse: ni revocada, ni quemada, ni vencida. */
    public boolean isRedeemable(Instant now) {
        return revokedAt == null && redeemedAt == null && expiresAt.isAfter(now);
    }

    public boolean isFrozen(Instant now) {
        return frozenUntil != null && frozenUntil.isAfter(now);
    }

    // ── OTP ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Registra un envío de código. Tope de {@code maxSends} por ventana de {@code window}.
     *
     * <p>El tope es por liga y no por teléfono porque la liga ES el teléfono: se acuña para un
     * número y sólo a ese número se manda. Contar por IP aquí no serviría —ella puede estar en el
     * wifi de la tienda junto con otras diez clientas—; ese eje le toca al gateway.
     *
     * <p>Mandar un código nuevo invalida el anterior: sólo hay uno vigente a la vez, y así un
     * mensaje viejo que llega tarde no abre nada.
     */
    public void registerOtpSend(String newOtpHash, Instant otpExpiry, Instant now,
                                int maxSends, Duration window) {
        assertNotFrozen(now);
        if (otpWindowStartedAt == null || !otpWindowStartedAt.plus(window).isAfter(now)) {
            otpWindowStartedAt = now;
            otpSendCount       = 0;
        }
        if (otpSendCount >= maxSends) {
            throw new PublicKycException.TooManyAttempts(
                    "Ya enviamos varios códigos. Espera unos minutos antes de pedir otro.",
                    Duration.between(now, otpWindowStartedAt.plus(window)).toSeconds());
        }
        otpSendCount++;
        otpHash      = newOtpHash;
        otpExpiresAt = otpExpiry;
    }

    /**
     * Comprueba el código que ella tecleó. Si coincide, <b>lo consume</b>: un código abre una
     * sesión, no dos — es lo que vuelve inofensivo el doble toque en «Verificar».
     *
     * <p>Sin código vigente (nunca se pidió, ya venció, ya se usó) la respuesta es «incorrecto» y
     * <b>no cuenta como intento</b>: no hay nada que adivinar, y castigarlo congelaría la liga de
     * quien tocó dos veces el botón. Lo que cuenta es teclear mal un código que sí existe.
     *
     * @return el hash del código verificado, para atarlo a la sesión que se acuña
     */
    public String verifyOtp(String candidateHash, Instant now, int maxFailures, Duration freeze) {
        assertNotFrozen(now);
        if (otpHash == null || otpExpiresAt == null || !otpExpiresAt.isAfter(now)) {
            throw new PublicKycException.WrongCode(maxFailures - failedAttempts);
        }
        if (!SecretHash.matches(otpHash, candidateHash)) {
            registerFailure(now, maxFailures, freeze);
            throw new PublicKycException.WrongCode(maxFailures - failedAttempts);
        }
        String verified = otpHash;
        otpHash        = null;
        otpExpiresAt   = null;
        failedAttempts = 0;
        return verified;
    }

    /**
     * Un código equivocado en cualquier punto de la liga —al verificar o al firmar—. Al quinto la
     * liga se congela una hora y se tira el código vigente: con 5 intentos sobre un millón la
     * probabilidad de acertar a ciegas es 0.0005%, y el congelamiento impide repetir la apuesta.
     */
    public void registerFailure(Instant now, int maxFailures, Duration freeze) {
        failedAttempts++;
        if (failedAttempts >= maxFailures) {
            frozenUntil    = now.plus(freeze);
            failedAttempts = 0;
            otpHash        = null;
            otpExpiresAt   = null;
            throw new PublicKycException.TooManyAttempts(
                    "Demasiados códigos incorrectos. Por seguridad, la liga quedó pausada una hora.",
                    freeze.toSeconds());
        }
    }

    public int attemptsLeft(int maxFailures) {
        return Math.max(maxFailures - failedAttempts, 0);
    }

    private void assertNotFrozen(Instant now) {
        if (isFrozen(now)) {
            throw new PublicKycException.TooManyAttempts(
                    "Por seguridad, la liga está pausada. Intenta más tarde.",
                    Duration.between(now, frozenUntil).toSeconds());
        }
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────────────────────

    /** Se quemó: produjo su expediente y ya no abre nada. */
    public void redeem(Instant now) {
        if (redeemedAt == null) redeemedAt = now;
        otpHash      = null;
        otpExpiresAt = null;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) revokedAt = now;
    }

    // ── Getters ──────────────────────────────────────────────────────────────────────────────

    public UUID getInviteId()            { return inviteId; }
    public UUID getPlacementId()         { return placementId; }
    public String getTokenHash()         { return tokenHash; }
    public Instant getExpiresAt()        { return expiresAt; }
    public Instant getRevokedAt()        { return revokedAt; }
    public Instant getRedeemedAt()       { return redeemedAt; }
    public String getOtpHash()           { return otpHash; }
    public Instant getOtpExpiresAt()     { return otpExpiresAt; }
    public int getOtpSendCount()         { return otpSendCount; }
    public Instant getOtpWindowStartedAt() { return otpWindowStartedAt; }
    public int getFailedAttempts()       { return failedAttempts; }
    public Instant getFrozenUntil()      { return frozenUntil; }
    public Instant getCreatedAt()        { return createdAt; }
}
