package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.OutboxMessage;

import java.util.List;
import java.util.UUID;

public interface OutboxMessageRepository {

    /**
     * Toma un lote de mensajes listos para enviar.
     *
     * <p>La implementación usa {@code FOR UPDATE SKIP LOCKED}: con varias réplicas, cada una toma
     * un lote distinto sin bloquearse. Es lo que resuelve la coordinación sin introducir ShedLock.
     */
    List<OutboxMessage> lockNextBatch(int batchSize);

    OutboxMessage save(OutboxMessage message);

    /** Necesario para registrar el fallo en una transacción nueva, tras el rollback de la anterior. */
    java.util.Optional<OutboxMessage> findById(UUID outboxId);

    List<OutboxMessage> findByAggregateId(UUID aggregateId);
}
