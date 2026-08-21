--liquibase formatted sql
--changeset beneficiary:003-create-placement-transitions author:system

CREATE TABLE beneficiary.placement_transitions (
    transition_id  UUID        NOT NULL,
    placement_id   UUID        NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20) NOT NULL,
    actor          VARCHAR(20) NOT NULL,
    actor_party_id UUID,
    reason         VARCHAR(500),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_placement_transitions PRIMARY KEY (transition_id),
    CONSTRAINT fk_placement_transitions_placement
        FOREIGN KEY (placement_id) REFERENCES beneficiary.placements (placement_id),

    CONSTRAINT chk_transition_actor CHECK (actor IN ('DISTRIBUTOR', 'BENEFICIARY', 'SYSTEM')),

    -- from_status nulo sólo en el alta.
    CONSTRAINT chk_transition_origin CHECK (from_status IS NOT NULL OR to_status = 'INVITED'),

    -- Si el actor es el distribuidor, sabemos cuál. Una decisión sin firma no sirve de evidencia.
    CONSTRAINT chk_transition_distributor_identified CHECK (
        actor <> 'DISTRIBUTOR' OR actor_party_id IS NOT NULL)
);

CREATE INDEX idx_placement_transitions_placement
    ON beneficiary.placement_transitions (placement_id, occurred_at);
