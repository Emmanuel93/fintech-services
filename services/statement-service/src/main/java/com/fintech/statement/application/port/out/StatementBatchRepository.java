package com.fintech.statement.application.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Las corridas de corte vistas desde aquí: cuántos estados de cuenta se esperan. Es la cifra contra
 * la que se mide el avance de la generación.
 */
public interface StatementBatchRepository {

    void planned(UUID runId, LocalDate businessDate, int expected, String breakdownJson);

    void cutoffCompleted(UUID runId, LocalDate businessDate, String cutoffStatus, int done, int failed);

    Optional<Batch> find(UUID runId);

    Optional<Batch> findByDate(LocalDate businessDate);

    record Batch(UUID runId, LocalDate businessDate, int expected, String breakdownJson, String cutoffStatus,
                 int cutoffDone, int cutoffFailed, Instant plannedAt, Instant cutoffCompletedAt) {}
}
