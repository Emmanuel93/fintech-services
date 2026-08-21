package com.fintech.origination.application.port.in;

import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FindCreditApplicationUseCase {

    CreditApplication getById(UUID applicationId);

    List<CreditApplication> findByProspectId(UUID prospectId);

    /** Bandeja paginada filtrable (ver {@code CreditApplicationRepository#search}). */
    Page<CreditApplication> search(Collection<ApplicationStatus> statuses,
                                   ProductType productType,
                                   Collection<ProductType> productTypes,
                                   UUID prospectId,
                                   Instant from,
                                   Instant to,
                                   String q,
                                   Pageable pageable);
}
