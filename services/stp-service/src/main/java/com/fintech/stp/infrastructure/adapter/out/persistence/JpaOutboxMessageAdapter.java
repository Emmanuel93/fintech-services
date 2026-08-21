package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.OutboxMessageRepository;
import com.fintech.stp.domain.OutboxMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador con clase, no interfaz: el {@code SKIP LOCKED} necesita paginación y reloj, y eso no
 * cabe en una interfaz derivada de Spring Data.
 */
@Component
public class JpaOutboxMessageAdapter implements OutboxMessageRepository {

    private final SpringDataOutboxRepository repository;
    private final Clock clock;

    JpaOutboxMessageAdapter(SpringDataOutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public List<OutboxMessage> lockNextBatch(int batchSize) {
        return repository.lockNextBatch(clock.instant(), PageRequest.of(0, batchSize));
    }

    @Override
    public OutboxMessage save(OutboxMessage message) {
        return repository.save(message);
    }

    @Override
    public java.util.Optional<OutboxMessage> findById(UUID outboxId) {
        return repository.findById(outboxId);
    }

    @Override
    public List<OutboxMessage> findByAggregateId(UUID aggregateId) {
        return repository.findByAggregateId(aggregateId);
    }
}
