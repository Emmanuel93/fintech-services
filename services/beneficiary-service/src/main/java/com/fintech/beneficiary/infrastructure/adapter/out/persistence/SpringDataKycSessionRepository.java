package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.KycSessionRepository;
import com.fintech.beneficiary.domain.KycSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataKycSessionRepository
        extends JpaRepository<KycSession, UUID>, KycSessionRepository {

    @Override
    Optional<KycSession> findById(UUID sessionId);

    @Override
    Optional<KycSession> findBySessionTokenHash(String sessionTokenHash);

    @Override
    @Query("SELECT s.inviteId FROM KycSession s WHERE s.sessionTokenHash = :hash")
    Optional<UUID> findInviteIdBySessionTokenHash(@Param("hash") String sessionTokenHash);

    @Override
    Optional<KycSession> findFirstByPlacementIdOrderByCreatedAtDesc(UUID placementId);

    @Override
    @Modifying
    @Query("""
            UPDATE KycSession s SET s.closedAt = :now, s.updatedAt = :now
             WHERE s.placementId = :placementId AND s.closedAt IS NULL
            """)
    int closeOpenSessions(@Param("placementId") UUID placementId, @Param("now") Instant now);

    // REQUIRES_NEW: se confirma aunque el cierre que la rodea se caiga después. Toca sólo la fila
    // de la sesión, que el cierre no tiene bloqueada —el candado es sobre la liga—, así que no
    // puede esperar contra su propia transacción.
    @Override
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE KycSession s SET s.prospectId = :prospectId
             WHERE s.sessionId = :sessionId AND s.prospectId IS NULL
            """)
    int rememberProspect(@Param("sessionId") UUID sessionId, @Param("prospectId") UUID prospectId);
}
