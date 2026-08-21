package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.CommunicationHoldRepository;
import com.fintech.collections.domain.CommunicationHold;
import com.fintech.collections.domain.HoldReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaCommunicationHoldRepository
        extends JpaRepository<CommunicationHold, UUID>, CommunicationHoldRepository {

    @Override
    @Query("SELECT h FROM CommunicationHold h WHERE h.caseId = :caseId AND h.reason = :reason "
         + "AND h.releasedAt IS NULL AND h.heldUntil > :moment")
    Optional<CommunicationHold> findActive(@Param("caseId") UUID caseId,
                                            @Param("reason") HoldReason reason,
                                            @Param("moment") Instant moment);

    @Override
    @Query("SELECT h FROM CommunicationHold h WHERE h.caseId = :caseId "
         + "AND h.releasedAt IS NULL AND h.heldUntil > :moment ORDER BY h.heldUntil DESC")
    List<CommunicationHold> findAllActive(@Param("caseId") UUID caseId, @Param("moment") Instant moment);

    @Override
    List<CommunicationHold> findByCaseIdOrderByCreatedAtDesc(UUID caseId);

    @Override
    @Query("SELECT DISTINCT h.caseId FROM CommunicationHold h "
         + "WHERE h.releasedAt IS NULL AND h.heldUntil > :moment")
    List<UUID> findCaseIdsWithActiveHold(@Param("moment") Instant moment);
}
