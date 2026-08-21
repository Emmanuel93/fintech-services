package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fintech.audit.application.port.out.AuditEntryRepository;
import com.fintech.audit.domain.AuditEntry;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAuditEntryAdapter implements AuditEntryRepository {

    private final SpringDataAuditEntryRepository jpa;

    JpaAuditEntryAdapter(SpringDataAuditEntryRepository jpa) {
        this.jpa = jpa;
    }

    /**
     * El límite se traduce a la primera página, no a un {@code Pageable} completo.
     *
     * <p>La bitácora no se pagina hacia atrás: se consulta con filtros y se lee lo más reciente.
     * Ofrecer número de página sobre una tabla que crece cada segundo daría resultados
     * inconsistentes entre páginas —lo que era la fila 200 deja de serlo mientras se navega—, y
     * para irse más atrás en el tiempo ya está {@code from}/{@code to}, que sí es estable.
     */
    private static Pageable firstPage(int limit) {
        return PageRequest.ofSize(limit);
    }

    @Override public AuditEntry save(AuditEntry entry) { return jpa.save(entry); }
    @Override public Optional<AuditEntry> findById(UUID id) { return jpa.findById(id); }

    @Override
    public List<AuditEntry> findByPartyId(String partyId, Instant from, Instant to, int limit) {
        return jpa.findByPartyId(partyId, from, to, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByAggregateId(String aggregateId, int limit) {
        return jpa.findByAggregateIdOrderByCreatedAtDesc(aggregateId, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByEventType(String eventType, Instant from, Instant to, int limit) {
        return jpa.findByEventType(eventType, from, to, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByActor(String actor, Instant from, Instant to, int limit) {
        return jpa.findByActor(actor, from, to, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByActorIp(String actorIp, Instant from, Instant to, int limit) {
        return jpa.findByActorIp(actorIp, from, to, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByAction(String action, Instant from, Instant to, int limit) {
        return jpa.findByAction(action, from, to, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByCategory(String category, Instant from, Instant to, int limit) {
        return jpa.findByCategory(category, from, to, firstPage(limit));
    }

    @Override
    public List<AuditEntry> findByDateRange(Instant from, Instant to, int limit) {
        return jpa.findByDateRange(from, to, firstPage(limit));
    }
}
