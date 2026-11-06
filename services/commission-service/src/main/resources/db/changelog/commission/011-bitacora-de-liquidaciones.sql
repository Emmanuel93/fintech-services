--liquibase formatted sql
--changeset commission:011-bitacora-de-liquidaciones
--comment Each liquidation run as it ran: the period it liquidated, when, and what it did. The self-checks read it to know up to which period the open accruals must be explained.

CREATE TABLE commission.liquidation_runs (
    run_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    period       VARCHAR(6)   NOT NULL,
    run_at       TIMESTAMPTZ  NOT NULL,
    lots_opened  INTEGER      NOT NULL,
    carried_over INTEGER      NOT NULL,
    waiting      INTEGER      NOT NULL,
    retried      INTEGER      NOT NULL,
    CONSTRAINT pk_liquidation_runs PRIMARY KEY (run_id)
);

CREATE INDEX idx_liquidation_runs_period ON commission.liquidation_runs (period, run_at);
