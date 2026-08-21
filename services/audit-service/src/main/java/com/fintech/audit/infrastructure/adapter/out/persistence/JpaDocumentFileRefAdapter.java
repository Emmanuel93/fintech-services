package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fintech.audit.application.port.out.DocumentFileRefRepository;
import com.fintech.audit.domain.DocumentFileRef;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaDocumentFileRefAdapter implements DocumentFileRefRepository {

    private final SpringDataDocumentFileRefRepository jpa;

    JpaDocumentFileRefAdapter(SpringDataDocumentFileRefRepository jpa) {
        this.jpa = jpa;
    }

    @Override public DocumentFileRef save(DocumentFileRef ref) { return jpa.save(ref); }
    @Override public Optional<DocumentFileRef> findById(UUID id) { return jpa.findById(id); }

    @Override
    public List<DocumentFileRef> findByPartyId(UUID partyId) {
        return jpa.findByPartyIdOrderByCreatedAtDesc(partyId);
    }

    @Override
    public List<DocumentFileRef> findByAggregateId(String aggregateId) {
        return jpa.findByAggregateIdOrderByCreatedAtDesc(aggregateId);
    }
}
