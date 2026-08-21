package com.fintech.collections.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Un periodo en que la cobranza automática de un caso está callada.
 *
 * <p><b>Sólo calla lo automático.</b> El agente puede seguir llamando: puede haber una razón para
 * hacerlo —confirmar que el pago salió, cerrar el convenio— y quitarle esa opción convertiría el
 * freno en un escondite donde los casos se pierden.
 *
 * <p>Se guarda como historial, no como bandera en el caso: importa poder contestar «por qué no se
 * le escribió a esta persona entre el 3 y el 12» sin reconstruirlo de otros hechos. Un caso puede
 * acumular varios frenos a lo largo de su vida y solaparse entre sí; vale el más lejano.
 */
@Entity
@Table(name = "communication_holds", schema = "collections")
public class CommunicationHold {

    @Id
    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private HoldReason reason;

    @Column(name = "held_until", nullable = false)
    private Instant heldUntil;

    /** Referencia al hecho que lo abrió: la promesa, el convenio, la asignación. */
    @Column(name = "source_id", updatable = false)
    private UUID sourceId;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by")
    private String releasedBy;

    @Column(name = "release_note")
    private String releaseNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommunicationHold() {}

    public static CommunicationHold open(UUID caseId, HoldReason reason, Instant heldUntil, UUID sourceId) {
        CommunicationHold h = new CommunicationHold();
        h.holdId    = UUID.randomUUID();
        h.caseId    = caseId;
        h.reason    = reason;
        h.heldUntil = heldUntil;
        h.sourceId  = sourceId;
        h.createdAt = Instant.now();
        return h;
    }

    /**
     * Extiende el freno si la fecha nueva es posterior.
     *
     * <p>Nunca lo acorta: un abono parcial alarga la paciencia, y si un hecho posterior pudiera
     * recortar el silencio que otro concedió, el orden de llegada de dos eventos decidiría cuánto
     * se le insiste a alguien.
     */
    public void extendTo(Instant nuevaFecha) {
        if (nuevaFecha != null && nuevaFecha.isAfter(heldUntil)) {
            this.heldUntil = nuevaFecha;
        }
    }

    /** Lo levanta antes de tiempo. `by` nulo = lo levantó el sistema (promesa rota). */
    public void release(String by, String note) {
        if (releasedAt != null) return;
        this.releasedAt  = Instant.now();
        this.releasedBy  = by;
        this.releaseNote = note;
    }

    public boolean isActiveAt(Instant moment) {
        return releasedAt == null && heldUntil.isAfter(moment);
    }

    public UUID getHoldId()        { return holdId; }
    public UUID getCaseId()        { return caseId; }
    public HoldReason getReason()  { return reason; }
    public Instant getHeldUntil()  { return heldUntil; }
    public UUID getSourceId()      { return sourceId; }
    public Instant getReleasedAt() { return releasedAt; }
    public String getReleasedBy()  { return releasedBy; }
    public String getReleaseNote() { return releaseNote; }
    public Instant getCreatedAt()  { return createdAt; }
}
