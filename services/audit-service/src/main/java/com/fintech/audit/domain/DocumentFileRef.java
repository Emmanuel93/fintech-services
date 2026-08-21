package com.fintech.audit.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "audit", name = "document_file_refs")
public class DocumentFileRef {

    @Id
    private UUID docId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType docType;

    private UUID partyId;
    private String aggregateId;

    @Column(nullable = false)
    private String storageRef;

    @Column(nullable = false)
    private int retentionYears;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant retainUntil;

    protected DocumentFileRef() {}

    public static DocumentFileRef create(DocumentType docType, UUID partyId,
                                         String aggregateId, String storageRef) {
        var d = new DocumentFileRef();
        d.docId         = UUID.randomUUID();
        d.docType       = docType;
        d.partyId       = partyId;
        d.aggregateId   = aggregateId;
        d.storageRef    = storageRef;
        d.retentionYears = docType.retentionYears();
        d.createdAt     = Instant.now();
        d.retainUntil   = d.createdAt.plus(
                java.time.Period.ofYears(d.retentionYears));
        return d;
    }

    public UUID getDocId()           { return docId; }
    public DocumentType getDocType() { return docType; }
    public UUID getPartyId()         { return partyId; }
    public String getAggregateId()   { return aggregateId; }
    public String getStorageRef()    { return storageRef; }
    public int getRetentionYears()   { return retentionYears; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getRetainUntil()  { return retainUntil; }
}
