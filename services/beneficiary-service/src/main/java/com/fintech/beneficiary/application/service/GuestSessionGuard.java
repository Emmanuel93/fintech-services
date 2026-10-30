package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.out.KycSessionRepository;
import com.fintech.beneficiary.application.port.out.PlacementInviteRepository;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.KycSession;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementInvite;
import com.fintech.beneficiary.domain.PublicKycException;
import com.fintech.beneficiary.domain.SecretHash;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Quién puede hablar con la superficie pública, y con qué.
 *
 * <p>Dos credenciales y ninguna es un JWT: el <b>token de la liga</b> (abre la portada y pide OTP)
 * y el <b>token de sesión</b> (abre los pasos del KYC, 15 minutos deslizantes). Ambas se comparan
 * por hash. Este componente es el «filtro de token» que {@code SecurityConfig} promete: la cadena
 * de Spring deja pasar {@code /public/**} porque la autorización de verdad ocurre aquí.
 */
@Component
public class GuestSessionGuard {

    private final PlacementInviteRepository invites;
    private final KycSessionRepository sessions;
    private final PlacementRepository placements;
    private final BeneficiaryProperties props;
    private final Clock clock;

    public GuestSessionGuard(PlacementInviteRepository invites, KycSessionRepository sessions,
                             PlacementRepository placements, BeneficiaryProperties props, Clock clock) {
        this.invites    = invites;
        this.sessions   = sessions;
        this.placements = placements;
        this.props      = props;
        this.clock      = clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    /**
     * La liga y su colocación, si la liga todavía abre algo.
     *
     * <p>Todas las razones de rechazo producen <b>la misma excepción con el mismo mensaje</b>: un
     * token que nunca existió y uno que venció ayer tienen que ser indistinguibles desde afuera.
     */
    public Placement requireLivePlacement(PlacementInvite invite) {
        if (invite == null || !invite.isRedeemable(now())) {
            throw new PublicKycException.InviteNotValid();
        }
        Placement placement = placements.findById(invite.getPlacementId())
                .orElseThrow(PublicKycException.InviteNotValid::new);
        // La colocación manda sobre la liga: cancelada o vencida la colocación, la liga muere aunque
        // su propia fecha diga otra cosa.
        if (!placement.inviteIsLive() || placement.inviteHasExpired(now())) {
            throw new PublicKycException.InviteNotValid();
        }
        return placement;
    }

    public PlacementInvite findInvite(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new PublicKycException.InviteNotValid();
        return invites.findByTokenHash(SecretHash.sha256Hex(rawToken))
                .orElseThrow(PublicKycException.InviteNotValid::new);
    }

    public PlacementInvite lockInvite(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw new PublicKycException.InviteNotValid();
        return invites.lockByTokenHash(SecretHash.sha256Hex(rawToken))
                .orElseThrow(PublicKycException.InviteNotValid::new);
    }

    /** La sesión, aunque esté cerrada. Basta para leer que ya terminó. */
    public KycSession findAliveSession(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            throw new PublicKycException.SessionExpired();
        }
        KycSession session = sessions.findBySessionTokenHash(SecretHash.sha256Hex(rawSessionToken))
                .orElseThrow(PublicKycException.SessionExpired::new);
        if (!session.isAlive(now())) throw new PublicKycException.SessionExpired();
        return session;
    }

    /**
     * Una sesión con la que se puede <b>avanzar</b>: abierta, vigente, con su liga viva y su
     * colocación todavía esperando KYC. Y la desliza: cada paso le da otros 15 minutos.
     *
     * <p>Si el distribuidor revoca la liga a media captura, la siguiente petición de ella responde
     * «sesión expirada» y no un error de estado: desde su lado, la sesión terminó.
     */
    public OpenSession requireOpenSession(String rawSessionToken) {
        KycSession session = findAliveSession(rawSessionToken);
        if (session.isClosed()) throw new PublicKycException.SessionExpired();
        PlacementInvite invite = invites.findById(session.getInviteId())
                .orElseThrow(PublicKycException.SessionExpired::new);
        Placement placement;
        try {
            placement = requireLivePlacement(invite);
        } catch (PublicKycException.InviteNotValid e) {
            throw new PublicKycException.SessionExpired();
        }
        session.slide(sessionExpiry(invite), now());
        sessions.save(session);
        return new OpenSession(session, invite, placement);
    }

    /** 15 minutos desde ahora, sin pasar del vencimiento de la liga. */
    public Instant sessionExpiry(PlacementInvite invite) {
        Instant sliding = now().plus(props.getGuestSessionTtlMinutes(), ChronoUnit.MINUTES);
        return sliding.isBefore(invite.getExpiresAt()) ? sliding : invite.getExpiresAt();
    }

    public record OpenSession(KycSession session, PlacementInvite invite, Placement placement) {}
}
