package com.fintech.origination.infrastructure.adapter.out.persistence;

import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCreditApplicationRepository extends JpaRepository<CreditApplication, UUID> {

    List<CreditApplication> findByProspectId(UUID prospectId);

    /**
     * Bandeja del backoffice. Filtros nulos no filtran (guardas {@code IS NULL});
     * {@code q} llega ya en minúsculas y con comodines (lo arma el adapter), para
     * no envolver el parámetro en {@code LOWER()} y poder apoyarse en el índice.
     *
     * <p>{@code from}/{@code to} <b>siempre llegan con valor</b> (el adapter
     * sustituye los nulos por límites abiertos): un {@code :param IS NULL} sobre
     * un parámetro temporal no tiene tipo determinable en Postgres y la consulta
     * revienta con "could not determine data type of parameter". Lo que sí
     * funciona para enums/UUID/colecciones no aplica a {@code timestamptz}.
     */
    @Query("SELECT a FROM CreditApplication a "
         + "WHERE (:statuses IS NULL OR a.status IN :statuses) "
         + "  AND (:productType IS NULL OR a.productType = :productType) "
         + "  AND (:productTypes IS NULL OR a.productType IN :productTypes) "
         + "  AND (:prospectId IS NULL OR a.prospectId = :prospectId) "
         + "  AND a.createdAt >= :from "
         + "  AND a.createdAt < :to "
         + "  AND (:q IS NULL OR LOWER(a.promoterCode) LIKE :q)")
    Page<CreditApplication> search(@Param("statuses") Collection<ApplicationStatus> statuses,
                                   @Param("productType") ProductType productType,
                                   @Param("productTypes") Collection<ProductType> productTypes,
                                   @Param("prospectId") UUID prospectId,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   @Param("q") String q,
                                   Pageable pageable);

    boolean existsByProspectIdAndProductTypeAndStatusNotIn(
            UUID prospectId, ProductType productType, Collection<ApplicationStatus> statuses);

    Optional<CreditApplication> findFirstByProspectIdAndProductTypeAndStatusNotIn(
            UUID prospectId, ProductType productType, Collection<ApplicationStatus> statuses);

    /** UW-06: most recent rejection within the cooldown window for this prospect+product. */
    Optional<CreditApplication> findFirstByProspectIdAndProductTypeAndStatusAndRejectedAtAfterOrderByRejectedAtDesc(
            UUID prospectId, ProductType productType, ApplicationStatus status, Instant since);

    /** Offer TTL expiry: OFFER_PRESENTED applications whose offer.validUntil has passed. */
    List<CreditApplication> findByStatusAndOffer_ValidUntilBefore(ApplicationStatus status, Instant now);

    /** Documents TTL expiry: PENDING_DOCUMENTS applications whose deadline has passed. */
    List<CreditApplication> findByStatusAndDocumentsDeadlineBefore(ApplicationStatus status, Instant now);
}
