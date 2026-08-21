package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditApplicationRepository {

    CreditApplication save(CreditApplication application);

    Optional<CreditApplication> findById(UUID applicationId);

    List<CreditApplication> findByProspectId(UUID prospectId);

    /**
     * Bandeja paginada del backoffice. Todos los filtros son opcionales:
     * <ul>
     *   <li>{@code statuses} — conjunto de estados (la bandeja de underwriting fija
     *       los estados en revisión; la general pasa el estado elegido o ninguno).</li>
     *   <li>{@code productType} — igualdad exacta; {@code productTypes} — el conjunto
     *       de una audiencia (B2C/B2B2C/B2B). Ambos se combinan con AND.</li>
     *   <li>{@code prospectId} — acota a un sujeto; sin él es bandeja por población.</li>
     *   <li>{@code from}/{@code to} — rango sobre {@code createdAt} ({@code to} exclusivo).</li>
     *   <li>{@code q} — texto libre contra {@code promoterCode} (lo que teclea el humano).</li>
     * </ul>
     */
    Page<CreditApplication> search(Collection<ApplicationStatus> statuses,
                                   ProductType productType,
                                   Collection<ProductType> productTypes,
                                   UUID prospectId,
                                   Instant from,
                                   Instant to,
                                   String q,
                                   Pageable pageable);

    /** OA-03: an active (non-terminal) application already exists for this prospect+product. */
    boolean existsActiveByProspectIdAndProductType(UUID prospectId, ProductType productType);

    /** The active (non-terminal) application for this prospect+product, if any (Phase C correlation). */
    Optional<CreditApplication> findActiveByProspectIdAndProductType(UUID prospectId, ProductType productType);

    /**
     * UW-06 cooldown: find the most recent rejection for this prospect+product after a given date.
     * Returns non-empty if a cooldown is still active.
     */
    Optional<CreditApplication> findMostRecentRejectionAfter(UUID prospectId, ProductType productType,
                                                               Instant since);

    /** Offer expiration job: find all OFFER_PRESENTED applications whose validUntil has passed. */
    List<CreditApplication> findExpiredOffers(Instant now);

    /** TTL de documentos: solicitudes en PENDING_DOCUMENTS cuyo plazo ya venció. */
    List<CreditApplication> findPendingDocumentsPastDeadline(Instant now);
}
