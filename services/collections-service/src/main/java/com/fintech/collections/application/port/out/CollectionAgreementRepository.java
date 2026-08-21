package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.AgreementStatus;
import com.fintech.collections.domain.CollectionAgreement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionAgreementRepository {
    Optional<CollectionAgreement> findById(UUID agreementId);
    /** AG-01: at most one PROPOSED/ACCEPTED agreement per case. */
    Optional<CollectionAgreement> findActiveByCaseId(UUID caseId);
    /** For AgreementExpirationJob — PROPOSED agreements older than the response window. */
    List<CollectionAgreement> findProposedBefore(Instant cutoff);
    CollectionAgreement save(CollectionAgreement agreement);

    /** Historial completo del caso: los rechazados y expirados también cuentan al revisar la gestión. */
    List<CollectionAgreement> findByCaseIdOrderByProposedAtDesc(UUID caseId);

    /**
     * Bandeja de autorización — los convenios que ya aceptó el deudor y esperan a un segundo par de
     * ojos. Es la mitad que faltaba del maker-checker: sin este listado, quien autoriza no tiene
     * dónde ver lo que le toca y el flujo depende de que alguien le pase el id por otro medio.
     */
    List<CollectionAgreement> findByStatusOrderByRespondedAtAsc(AgreementStatus status);
}
