package com.fintech.closing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una corrida: la fase de un día sobre un alcance.
 *
 * <p>El candado de Redis decide quién planifica; {@code uq_close_run} en la base es la
 * <b>segunda red</b>: si el candado fallara, la restricción sigue impidiendo dos corridas de la
 * misma fecha y fase. Depender sólo del candado sería depender de que Redis nunca se equivoque.
 */
@Entity
@Table(name = "close_runs", schema = "closing")
public class CloseRun {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "phase", nullable = false, length = 24)
    private String phase;

    @Column(name = "scope_key", nullable = false, length = 60)
    private String scopeKey;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "planned_units", nullable = false) private int plannedUnits;
    @Column(name = "done_units", nullable = false)    private int doneUnits;
    @Column(name = "failed_units", nullable = false)  private int failedUnits;
    @Column(name = "skipped_units", nullable = false) private int skippedUnits;

    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "sealed_at")  private Instant sealedAt;
    @Column(name = "last_error", length = 500) private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CloseRun() {}

    public static CloseRun plan(LocalDate businessDate, ClosePhase phase, String scopeKey) {
        CloseRun r = new CloseRun();
        r.runId        = UUID.randomUUID();
        r.businessDate = businessDate;
        r.phase        = phase.name();
        r.scopeKey     = scopeKey == null || scopeKey.isBlank() ? "ALL" : scopeKey;
        r.status       = RunStatus.PLANNED.name();
        r.createdAt    = Instant.now();
        return r;
    }

    public void materialized(int units) {
        this.plannedUnits = units;
        this.status       = RunStatus.RUNNING.name();
        this.startedAt    = Instant.now();
    }

    public void progress(int done, int failed, int skipped) {
        this.doneUnits    = done;
        this.failedUnits  = failed;
        this.skippedUnits = skipped;
    }

    /**
     * Sella. <b>No se sella con unidades fallidas</b>: un cierre a medias produce un número que
     * parece bueno y no lo es, que es el modo de falla que este sistema tiene que evitar.
     */
    public void seal() {
        if (failedUnits > 0) {
            throw new IllegalStateException(
                    "No se puede sellar " + phase + " de " + businessDate + " con "
                    + failedUnits + " unidades fallidas");
        }
        this.status   = RunStatus.SEALED.name();
        this.sealedAt = Instant.now();
    }

    public void fail(String error) {
        this.status    = RunStatus.FAILED.name();
        this.lastError = error != null && error.length() > 500 ? error.substring(0, 500) : error;
    }

    public boolean isSealed()  { return RunStatus.SEALED.name().equals(status); }
    public boolean isRunning() { return RunStatus.RUNNING.name().equals(status); }

    public UUID getRunId()             { return runId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public ClosePhase phase()          { return ClosePhase.valueOf(phase); }
    public String getScopeKey()        { return scopeKey; }
    public RunStatus status()          { return RunStatus.valueOf(status); }
    public int getPlannedUnits()       { return plannedUnits; }
    public int getDoneUnits()          { return doneUnits; }
    public int getFailedUnits()        { return failedUnits; }
    public int getSkippedUnits()       { return skippedUnits; }
    public Instant getSealedAt()       { return sealedAt; }
    public String getLastError()       { return lastError; }
}
