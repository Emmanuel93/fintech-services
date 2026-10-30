package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.PlacementInvite;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlacementInviteRepository {

    PlacementInvite save(PlacementInvite invite);

    Optional<PlacementInvite> findById(UUID inviteId);

    Optional<PlacementInvite> findByTokenHash(String tokenHash);

    /**
     * La liga, con la fila bloqueada hasta el fin de la transacción.
     *
     * <p>Es lo que serializa a dos pestañas —o a un doble toque— sobre la misma liga: verificar un
     * OTP lo consume y firmar crea un prospecto, y ninguna de las dos cosas puede pasar dos veces.
     */
    Optional<PlacementInvite> lockByTokenHash(String tokenHash);

    Optional<PlacementInvite> lockById(UUID inviteId);

    /** La liga viva de la colocación, si la hay. */
    Optional<PlacementInvite> findLiveByPlacementId(UUID placementId);

    /** Revoca toda liga viva de la colocación. Se ejecuta antes de acuñar la nueva. */
    int revokeLiveInvites(UUID placementId, Instant now);
}
