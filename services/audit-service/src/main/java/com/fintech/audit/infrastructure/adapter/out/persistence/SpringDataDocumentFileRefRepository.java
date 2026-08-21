package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fintech.audit.domain.DocumentFileRef;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataDocumentFileRefRepository extends JpaRepository<DocumentFileRef, UUID> {
    List<DocumentFileRef> findByPartyIdOrderByCreatedAtDesc(UUID partyId);
    List<DocumentFileRef> findByAggregateIdOrderByCreatedAtDesc(String aggregateId);
}
