package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.PlacementInviteRepository;
import com.fintech.beneficiary.domain.PlacementInvite;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataPlacementInviteRepository
        extends JpaRepository<PlacementInvite, UUID>, PlacementInviteRepository {

    @Override
    Optional<PlacementInvite> findById(UUID inviteId);

    @Override
    Optional<PlacementInvite> findByTokenHash(String tokenHash);

    // `SELECT … FOR UPDATE`: la segunda pestaña espera a que la primera termine y entonces ve el
    // OTP ya consumido, o el prospecto ya creado.
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PlacementInvite i WHERE i.tokenHash = :tokenHash")
    Optional<PlacementInvite> lockByTokenHash(@Param("tokenHash") String tokenHash);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM PlacementInvite i WHERE i.inviteId = :inviteId")
    Optional<PlacementInvite> lockById(@Param("inviteId") UUID inviteId);

    @Override
    @Query("""
            SELECT i FROM PlacementInvite i
             WHERE i.placementId = :placementId
               AND i.revokedAt IS NULL
               AND i.redeemedAt IS NULL
            """)
    Optional<PlacementInvite> findLiveByPlacementId(@Param("placementId") UUID placementId);

    // Un UPDATE directo y no cargar-y-guardar: Hibernate ordena los INSERT antes que los UPDATE al
    // vaciar la sesión, así que la liga nueva chocaría con el índice de «una viva por colocación»
    // antes de que la vieja quedara revocada.
    @Override
    @Modifying
    @Query("""
            UPDATE PlacementInvite i SET i.revokedAt = :now
             WHERE i.placementId = :placementId
               AND i.revokedAt IS NULL
               AND i.redeemedAt IS NULL
            """)
    int revokeLiveInvites(@Param("placementId") UUID placementId, @Param("now") Instant now);
}
