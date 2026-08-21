package com.fintech.audit.application.service;

import com.fintech.audit.application.port.out.DocumentFileRefRepository;
import com.fintech.audit.domain.DocumentFileRef;
import com.fintech.audit.domain.DocumentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class DocumentArchiveService {

    private static final Logger log = LoggerFactory.getLogger(DocumentArchiveService.class);

    private final DocumentFileRefRepository repository;

    public DocumentArchiveService(DocumentFileRefRepository repository) {
        this.repository = repository;
    }

    public DocumentFileRef archive(DocumentType docType, UUID partyId,
                                   String aggregateId, String storageRef) {
        DocumentFileRef ref = DocumentFileRef.create(docType, partyId, aggregateId, storageRef);
        repository.save(ref);
        log.info("document archived docType={} aggregateId={} retainUntil={}", docType, aggregateId, ref.getRetainUntil());
        return ref;
    }

    @Transactional(readOnly = true)
    public Optional<DocumentFileRef> findById(UUID docId) {
        return repository.findById(docId);
    }

    @Transactional(readOnly = true)
    public List<DocumentFileRef> findByPartyId(UUID partyId) {
        return repository.findByPartyId(partyId);
    }

    @Transactional(readOnly = true)
    public List<DocumentFileRef> findByAggregateId(String aggregateId) {
        return repository.findByAggregateId(aggregateId);
    }
}
