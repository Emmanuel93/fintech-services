package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.ContactAttemptRepository;
import com.fintech.collections.domain.ContactAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JpaContactAttemptRepository
        extends JpaRepository<ContactAttempt, UUID>, ContactAttemptRepository {

    @Override
    List<ContactAttempt> findByCaseId(UUID caseId);

    @Override
    @Query("SELECT COUNT(a) FROM ContactAttempt a WHERE a.caseId = :caseId "
         + "AND a.attemptedAt > :since AND a.origin = com.fintech.collections.domain.ContactOrigin.MANUAL")
    long countManualByCaseIdAndAttemptedAtAfter(@Param("caseId") UUID caseId, @Param("since") Instant since);

    @Override
    long countByCaseIdAndAttemptedAtAfter(UUID caseId, Instant since);
}
