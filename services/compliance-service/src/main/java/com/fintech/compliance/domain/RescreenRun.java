package com.fintech.compliance.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Una corrida de re-cruce de la población viva contra las listas vigentes.
 *
 * <h2>Por qué existe como registro y no sólo como trabajo</h2>
 *
 * El negocio <b>asumió</b> la ventana entre que se activa una lista y que termina el re-cruce: los
 * veredictos vigentes siguen valiendo mientras corre, así que nada se detiene. Un riesgo asumido sin
 * constancia es indistinguible de un descuido — y la pregunta de una revisión no va a ser «¿corrían
 * re-cruces?» sino «¿cuándo corrió el que debía haber detectado esto, y cuánto tardó?».
 *
 * <p>Esta tabla responde eso: contra qué lista, cuándo empezó, cuándo terminó, a cuántos miró y a
 * cuántos les cambió el veredicto. Junto con la foto que cada disposición guarda, cierra la
 * historia: se puede demostrar que un desembolso salió <b>dentro</b> de la ventana conocida y no
 * fuera de ella.
 */
@Entity
@Table(name = "rescreen_runs", schema = "compliance")
public class RescreenRun {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    /**
     * La activación que lo disparó, si vino de una.
     *
     * <p>Nulo cuando lo dispara el calendario o una persona: un re-cruce periódico sin lista nueva
     * también sirve —las listas se corrigen sin cambiar de versión— y conviene poder distinguirlos.
     */
    @Column(name = "triggered_by_version_id")
    private UUID triggeredByVersionId;

    @Column(name = "trigger", length = 20, nullable = false)
    private String trigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RescreenRunStatus status;

    @Column(name = "total_screened", nullable = false) private int totalScreened;
    @Column(name = "changed_count",  nullable = false) private int changedCount;

    @Column(name = "failure_reason", length = 500) private String failureReason;

    @Column(name = "started_at",  nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;

    protected RescreenRun() { }

    public static RescreenRun start(UUID triggeredByVersionId, String trigger) {
        RescreenRun r = new RescreenRun();
        r.runId                = UUID.randomUUID();
        r.triggeredByVersionId = triggeredByVersionId;
        r.trigger              = trigger;
        r.status               = RescreenRunStatus.RUNNING;
        r.startedAt            = Instant.now();
        return r;
    }

    public void countScreened(boolean changed) {
        totalScreened++;
        if (changed) changedCount++;
    }

    public void finish() {
        this.status     = RescreenRunStatus.COMPLETED;
        this.finishedAt = Instant.now();
    }

    /**
     * Termina mal, y <b>conserva lo que sí alcanzó a re-cruzar</b>.
     *
     * <p>Los veredictos que se actualizaron antes del fallo quedan actualizados: son mejores que los
     * que había. Lo que no se puede hacer es dar la corrida por buena — la siguiente tiene que
     * volver a mirar a todos, porque no se sabe a quién no se llegó.
     */
    public void fail(String reason) {
        this.status         = RescreenRunStatus.FAILED;
        this.failureReason  = reason == null ? "sin motivo"
                : reason.substring(0, Math.min(500, reason.length()));
        this.finishedAt     = Instant.now();
    }

    /** Cuánto duró la ventana asumida en esta corrida. Es el dato que se compara contra la promesa. */
    public java.time.Duration duration() {
        return finishedAt == null ? null : java.time.Duration.between(startedAt, finishedAt);
    }

    public UUID getRunId()                 { return runId; }
    public UUID getTriggeredByVersionId()  { return triggeredByVersionId; }
    public String getTrigger()             { return trigger; }
    public RescreenRunStatus getStatus()   { return status; }
    public int getTotalScreened()          { return totalScreened; }
    public int getChangedCount()           { return changedCount; }
    public String getFailureReason()       { return failureReason; }
    public Instant getStartedAt()          { return startedAt; }
    public Instant getFinishedAt()         { return finishedAt; }
}
