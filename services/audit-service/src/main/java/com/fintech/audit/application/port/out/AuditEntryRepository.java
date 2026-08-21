package com.fintech.audit.application.port.out;

import com.fintech.audit.domain.AuditEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditEntryRepository {
    AuditEntry save(AuditEntry entry);
    Optional<AuditEntry> findById(UUID entryId);
    List<AuditEntry> findByPartyId(String partyId, Instant from, Instant to, int limit);
    List<AuditEntry> findByAggregateId(String aggregateId, int limit);
    List<AuditEntry> findByEventType(String eventType, Instant from, Instant to, int limit);
    List<AuditEntry> findByActor(String actor, Instant from, Instant to, int limit);
    List<AuditEntry> findByActorIp(String actorIp, Instant from, Instant to, int limit);
    List<AuditEntry> findByAction(String action, Instant from, Instant to, int limit);
    List<AuditEntry> findByCategory(String category, Instant from, Instant to, int limit);
    List<AuditEntry> findByDateRange(Instant from, Instant to, int limit);
}
