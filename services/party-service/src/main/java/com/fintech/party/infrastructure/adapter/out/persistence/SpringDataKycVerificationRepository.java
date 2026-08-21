package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.domain.KycVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataKycVerificationRepository extends JpaRepository<KycVerification, UUID> {

    @Query("""
            SELECT k FROM KycVerification k
            WHERE k.partyId = :partyId AND k.documentType = :documentType
            ORDER BY k.createdAt DESC
            LIMIT 1
            """)
    Optional<KycVerification> findLatestByPartyIdAndDocumentType(
            @Param("partyId") UUID partyId,
            @Param("documentType") String documentType);

    List<KycVerification> findAllByPartyId(UUID partyId);
}
