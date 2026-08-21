package com.fintech.collections.application.port.in;

import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.DelinquencyBucket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface GetCollectionCaseUseCase {
    CollectionCase getById(UUID caseId);
    CollectionCase getByCreditAccountId(UUID creditAccountId);

    /** La bandeja: casos filtrados y paginados. Todos los filtros son opcionales. */
    Page<CollectionCase> search(CaseStatus status,
                                DelinquencyBucket bucket,
                                String productType,
                                String assignedAgentId,
                                Integer minDaysDelinquent,
                                Pageable pageable);
}
