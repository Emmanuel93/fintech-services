package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.KycDocument;
import com.fintech.beneficiary.domain.KycDocumentSummary;
import com.fintech.beneficiary.domain.KycDocumentType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycDocumentRepository {

    KycDocument save(KycDocument document);

    Optional<KycDocument> findByPlacementIdAndDocumentType(UUID placementId, KycDocumentType type);

    List<KycDocument> findByPlacementId(UUID placementId);

    /** Lo que hay, sin los bytes. */
    List<KycDocumentSummary> summariesFor(UUID placementId);
}
