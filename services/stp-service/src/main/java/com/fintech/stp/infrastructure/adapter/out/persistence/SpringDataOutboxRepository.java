package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.domain.OutboxMessage;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT m FROM OutboxMessage m
            WHERE m.status = 'PENDING' AND m.nextAttemptAt <= :now
            ORDER BY m.nextAttemptAt
            """)
    List<OutboxMessage> lockNextBatch(@Param("now") Instant now, Pageable pageable);

    /** The same messages, counted without locking them (the partial index on PENDING serves both). */
    @Query("""
            SELECT count(m) FROM OutboxMessage m
            WHERE m.status = 'PENDING' AND m.nextAttemptAt <= :now
            """)
    long countDue(@Param("now") Instant now);

    List<OutboxMessage> findByAggregateId(UUID aggregateId);
}
