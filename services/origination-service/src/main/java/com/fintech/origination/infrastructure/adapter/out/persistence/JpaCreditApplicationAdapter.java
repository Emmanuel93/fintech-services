package com.fintech.origination.infrastructure.adapter.out.persistence;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JpaCreditApplicationAdapter implements CreditApplicationRepository {

    /** Límites abiertos para el rango de fechas cuando el filtro viene nulo. */
    private static final Instant OPEN_FROM = Instant.EPOCH;
    private static final Instant OPEN_TO   = Instant.parse("9999-01-01T00:00:00Z");

    /** Terminal/closed statuses — an application in any of these is not "active" (OA-03). */
    private static final Set<ApplicationStatus> TERMINAL = Set.of(
            ApplicationStatus.REJECTED,
            ApplicationStatus.FAILED,
            ApplicationStatus.CANCELLED,
            ApplicationStatus.OFFER_REJECTED,
            ApplicationStatus.OFFER_EXPIRED,
            ApplicationStatus.DISBURSED);

    private final SpringDataCreditApplicationRepository jpa;

    JpaCreditApplicationAdapter(SpringDataCreditApplicationRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public CreditApplication save(CreditApplication application) {
        return jpa.save(application);
    }

    @Override
    public Optional<CreditApplication> findById(UUID applicationId) {
        return jpa.findById(applicationId);
    }

    @Override
    public List<CreditApplication> findByProspectId(UUID prospectId) {
        return jpa.findByProspectId(prospectId);
    }

    @Override
    public Page<CreditApplication> search(Collection<ApplicationStatus> statuses,
                                          ProductType productType,
                                          Collection<ProductType> productTypes,
                                          UUID prospectId,
                                          Instant from,
                                          Instant to,
                                          String q,
                                          Pageable pageable) {
        // Colecciones vacías = sin filtro (null para que la guarda IS NULL las ignore).
        Collection<ApplicationStatus> statusFilter =
                (statuses == null || statuses.isEmpty()) ? null : statuses;
        Collection<ProductType> typeFilter =
                (productTypes == null || productTypes.isEmpty()) ? null : productTypes;
        // q listo para el LIKE: minúsculas + comodines (no se envuelve en LOWER() en la query).
        String text = (q == null || q.isBlank()) ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        // Rango de fechas siempre con valor: un parámetro temporal nulo en un
        // `IS NULL` no tiene tipo determinable en Postgres (ver la query).
        Instant fromBound = (from != null) ? from : OPEN_FROM;
        Instant toBound   = (to != null) ? to : OPEN_TO;
        return jpa.search(statusFilter, productType, typeFilter, prospectId, fromBound, toBound, text, pageable);
    }

    @Override
    public boolean existsActiveByProspectIdAndProductType(UUID prospectId, ProductType productType) {
        return jpa.existsByProspectIdAndProductTypeAndStatusNotIn(prospectId, productType, TERMINAL);
    }

    @Override
    public Optional<CreditApplication> findActiveByProspectIdAndProductType(UUID prospectId, ProductType productType) {
        return jpa.findFirstByProspectIdAndProductTypeAndStatusNotIn(prospectId, productType, TERMINAL);
    }

    @Override
    public Optional<CreditApplication> findMostRecentRejectionAfter(UUID prospectId, ProductType productType,
                                                                      Instant since) {
        return jpa.findFirstByProspectIdAndProductTypeAndStatusAndRejectedAtAfterOrderByRejectedAtDesc(
                prospectId, productType, ApplicationStatus.REJECTED, since);
    }

    @Override
    public List<CreditApplication> findExpiredOffers(Instant now) {
        return jpa.findByStatusAndOffer_ValidUntilBefore(ApplicationStatus.OFFER_PRESENTED, now);
    }

    @Override
    public List<CreditApplication> findPendingDocumentsPastDeadline(Instant now) {
        return jpa.findByStatusAndDocumentsDeadlineBefore(ApplicationStatus.PENDING_DOCUMENTS, now);
    }
}
