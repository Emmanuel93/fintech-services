package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.out.PlacementInviteRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementInvite;
import com.fintech.beneficiary.domain.PlacementNotReadyException;
import com.fintech.beneficiary.domain.SecretHash;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Acuña la liga de una colocación.
 *
 * <p>El token en claro sale de aquí <b>una sola vez</b>, en el valor de retorno, y no se guarda en
 * ningún lado: en base queda su hash. Quien lo recibe es responsable de entregarlo —hoy el soporte
 * de pruebas; mañana notifications por WhatsApp—.
 */
@Service
public class PlacementInviteService {

    private static final Logger log = LoggerFactory.getLogger(PlacementInviteService.class);

    private final PlacementInviteRepository invites;
    private final BeneficiaryProperties props;
    private final Clock clock;

    public PlacementInviteService(PlacementInviteRepository invites, BeneficiaryProperties props,
                                  Clock clock) {
        this.invites = invites;
        this.props   = props;
        this.clock   = clock;
    }

    /**
     * Acuña una liga nueva y revoca la que hubiera.
     *
     * <p>La vigencia es la de la colocación, no «7 días desde ahora»: rotar el token no puede
     * regalarle vida a una liga. Lo que reinicia la ventana es el reenvío, que es una decisión del
     * distribuidor con su propio tope.
     */
    @Transactional
    public MintedInvite mint(Placement placement) {
        if (!placement.inviteIsLive()) {
            throw new PlacementNotReadyException(
                    "La colocación " + placement.getPlacementId() + " ya no espera KYC: no se acuña liga");
        }
        Instant now = Instant.now(clock);
        int revoked = invites.revokeLiveInvites(placement.getPlacementId(), now);

        String token = SecretHash.randomToken();
        PlacementInvite invite = PlacementInvite.mint(
                placement.getPlacementId(), SecretHash.sha256Hex(token),
                placement.getInviteExpiresAt(), now);
        invites.save(invite);

        // Ni el token ni su hash van al log: el hash no abre nada, pero tampoco le sirve a nadie
        // que lo lea, y la costumbre de no loguear secretos se pierde de a uno.
        log.info("Liga acuñada para la colocación {} (invite {}, vence {}, revocadas {})",
                placement.getPlacementId(), invite.getInviteId(), invite.getExpiresAt(), revoked);
        return new MintedInvite(invite.getInviteId(), token, invite.getExpiresAt(), linkFor(token));
    }

    /** La URL que abre la web de KYC. El token va en la query, que es lo que la página lee. */
    public String linkFor(String token) {
        String base = props.getPublicKyc().getLinkBaseUrl();
        return base + (base.contains("?") ? "&" : "?") + "t=" + token;
    }

    /** El token en claro y su liga. Se entrega una vez; no hay forma de volver a pedirlo. */
    public record MintedInvite(UUID inviteId, String token, Instant expiresAt, String link) {}
}
