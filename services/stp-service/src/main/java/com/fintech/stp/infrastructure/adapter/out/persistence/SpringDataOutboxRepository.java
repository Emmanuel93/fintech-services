package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.domain.OutboxMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface SpringDataOutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * {@code FOR UPDATE SKIP LOCKED}: con varias réplicas, cada una toma un lote distinto sin
     * bloquearse. Es lo que resuelve la coordinación entre instancias sin traer ShedLock al repo.
     */
    @org.springframework.data.jpa.repository.Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT m FROM OutboxMessage m
            WHERE m.status = 'PENDING' AND m.nextAttemptAt <= :now
            ORDER BY m.nextAttemptAt
            """)
    List<OutboxMessage> lockNextBatch(@Param("now") Instant now,
                                      org.springframework.data.domain.Pageable pageable);

    List<OutboxMessage> findByAggregateId(UUID aggregateId);
}
