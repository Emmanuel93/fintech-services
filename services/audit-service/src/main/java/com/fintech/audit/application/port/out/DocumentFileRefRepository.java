package com.fintech.audit.application.port.out;

import com.fintech.audit.domain.DocumentFileRef;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentFileRefRepository {
    DocumentFileRef save(DocumentFileRef ref);
    Optional<DocumentFileRef> findById(UUID docId);
    List<DocumentFileRef> findByPartyId(UUID partyId);
    List<DocumentFileRef> findByAggregateId(String aggregateId);
}
