package com.fintech.collections.infrastructure.adapter.out.persistence;

import com.fintech.collections.application.port.out.CollectionAgreementRepository;
import com.fintech.collections.domain.CollectionAgreement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaCollectionAgreementRepository
        extends JpaRepository<CollectionAgreement, UUID>, CollectionAgreementRepository {

    @Override
    @Query("SELECT a FROM CollectionAgreement a WHERE a.caseId = :caseId AND a.status IN ('PROPOSED','ACCEPTED')")
    Optional<CollectionAgreement> findActiveByCaseId(@Param("caseId") UUID caseId);

    @Override
    @Query("SELECT a FROM CollectionAgreement a WHERE a.status = 'PROPOSED' AND a.proposedAt < :cutoff")
    List<CollectionAgreement> findProposedBefore(@Param("cutoff") Instant cutoff);
}
