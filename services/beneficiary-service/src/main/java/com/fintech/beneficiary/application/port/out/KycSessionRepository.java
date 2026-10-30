package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.KycSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface KycSessionRepository {

    KycSession save(KycSession session);

    Optional<KycSession> findById(UUID sessionId);

    Optional<KycSession> findBySessionTokenHash(String sessionTokenHash);

    /**
     * Sólo la liga de la sesión, sin cargar la sesión.
     *
     * <p>El cierre bloquea primero la liga y lee la sesión <b>después</b>: si la leyera antes, una
     * segunda pestaña vería la foto de antes de que la primera terminara —sin prospecto, sin
     * cerrar— y crearía otro expediente.
     */
    Optional<UUID> findInviteIdBySessionTokenHash(String sessionTokenHash);

    /** La sesión más reciente de la colocación: de ahí se retoma y de ahí lee la mesa de KYC. */
    Optional<KycSession> findFirstByPlacementIdOrderByCreatedAtDesc(UUID placementId);

    /** Cierra las sesiones abiertas de la colocación. Una sesión viva a la vez. */
    int closeOpenSessions(UUID placementId, Instant now);

    /**
     * Graba el prospecto en su propia transacción.
     *
     * <p>Es la única escritura del cierre que <b>tiene</b> que sobrevivir a un fallo posterior: si
     * party tarda y el cierre se cae, el prospecto ya existe en origination, y sin recordarlo el
     * reintento chocaría con la CURP duplicada.
     */
    int rememberProspect(UUID sessionId, UUID prospectId);
}
