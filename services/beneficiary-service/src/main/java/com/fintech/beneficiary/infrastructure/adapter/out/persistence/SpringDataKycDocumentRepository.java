package com.fintech.beneficiary.infrastructure.adapter.out.persistence;

import com.fintech.beneficiary.application.port.out.KycDocumentRepository;
import com.fintech.beneficiary.domain.KycDocument;
import com.fintech.beneficiary.domain.KycDocumentSummary;
import com.fintech.beneficiary.domain.KycDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataKycDocumentRepository
        extends JpaRepository<KycDocument, UUID>, KycDocumentRepository {

    @Override
    Optional<KycDocument> findByPlacementIdAndDocumentType(UUID placementId, KycDocumentType type);

    @Override
    List<KycDocument> findByPlacementId(UUID placementId);

    // Proyección por constructor: la lista de la ficha no arrastra los bytes de cada foto.
    @Override
    @Query("""
            SELECT new com.fintech.beneficiary.domain.KycDocumentSummary(
                       d.documentType, d.contentType, d.sizeBytes, d.uploadedAt)
              FROM KycDocument d
             WHERE d.placementId = :placementId
             ORDER BY d.documentType
            """)
    List<KycDocumentSummary> summariesFor(@Param("placementId") UUID placementId);
}
