package com.fintech.origination.infrastructure.adapter.out.persistence;

import com.fintech.origination.domain.ProspectDocumentFile;
import com.fintech.origination.domain.ProspectDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProspectDocumentFileRepository
        extends JpaRepository<ProspectDocumentFile, UUID> {

    Optional<ProspectDocumentFile> findByProspectIdAndDocumentType(UUID prospectId, ProspectDocumentType type);

    /**
     * Metadatos sin el contenido.
     *
     * Una proyección explícita y no la entidad: listar el expediente de un prospecto no debe
     * traerse los megabytes de sus fotos para acabar pintando cuatro nombres y sus tamaños.
     */
    @Query("""
           select new com.fintech.origination.infrastructure.adapter.out.persistence.DocumentFileSummary(
                    f.fileId, f.documentType, f.fileName, f.contentType, f.sizeBytes, f.uploadedAt,
                    f.reviewStatus, f.verificationSource, f.reviewedBy, f.reviewedAt, f.rejectionReason)
             from ProspectDocumentFile f
            where f.prospectId = :prospectId
            order by f.documentType
           """)
    List<DocumentFileSummary> summariesFor(UUID prospectId);
}
