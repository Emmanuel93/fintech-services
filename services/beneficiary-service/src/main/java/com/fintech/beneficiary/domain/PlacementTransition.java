package com.fintech.beneficiary.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Bitácora append-only de cada cambio de estado de una colocación.
 *
 * <p>No es auditoría regulatoria —de eso se encarga audit-service consumiendo los eventos— sino la
 * fuente del {@code timeline[]} que la pantalla 23 le pinta al distribuidor: qué pasó, cuándo y por
 * quién. Se guarda aquí y no se deriva de Kafka porque la app la consulta de forma síncrona y no
 * puede depender de la retención de un tópico.
 *
 * <p>El {@code actor} distingue las tres manos que mueven una colocación: el distribuidor
 * (decisiones), la beneficiaria (su KYC) y el sistema (buró, desembolso, vencimiento).
 */
@Entity
@Table(schema = "beneficiary", name = "placement_transitions")
public class PlacementTransition {

    public enum Actor { DISTRIBUTOR, BENEFICIARY, SYSTEM }

    @Id
    @Column(name = "transition_id", nullable = false, updatable = false)
    private UUID transitionId;

    @Column(name = "placement_id", nullable = false, updatable = false)
    private UUID placementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private PlacementStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private PlacementStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, updatable = false)
    private Actor actor;

    /** El party del distribuidor cuando el actor es DISTRIBUTOR; nulo en los otros dos casos. */
    @Column(name = "actor_party_id", updatable = false)
    private UUID actorPartyId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected PlacementTransition() {}

    public static PlacementTransition of(UUID placementId, PlacementStatus fromStatus,
                                         PlacementStatus toStatus, Actor actor,
                                         UUID actorPartyId, String reason) {
        PlacementTransition t = new PlacementTransition();
        t.transitionId = UUID.randomUUID();
        t.placementId  = placementId;
        t.fromStatus   = fromStatus;
        t.toStatus     = toStatus;
        t.actor        = actor;
        t.actorPartyId = actorPartyId;
        t.reason       = reason;
        t.occurredAt   = Instant.now();
        return t;
    }

    public UUID getTransitionId()        { return transitionId; }
    public UUID getPlacementId()         { return placementId; }
    public PlacementStatus getFromStatus() { return fromStatus; }
    public PlacementStatus getToStatus()   { return toStatus; }
    public Actor getActor()              { return actor; }
    public UUID getActorPartyId()        { return actorPartyId; }
    public String getReason()            { return reason; }
    public Instant getOccurredAt()       { return occurredAt; }
}
