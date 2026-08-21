-- D2 Scoring — Bureau prefetch aggregate (tracks each Círculo de Crédito query attempt)

CREATE TABLE scoring.bureau_prefetches (
    prefetch_id         UUID        NOT NULL,
    prospect_id         UUID        NOT NULL,
    curp                VARCHAR(18) NOT NULL,
    consent_ref         VARCHAR(100) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    requested_at        TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ,
    failure_reason      VARCHAR(500),

    CONSTRAINT pk_bureau_prefetches PRIMARY KEY (prefetch_id),
    CONSTRAINT ck_bureau_prefetch_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','PARTIAL','FAILED'))
);

CREATE INDEX idx_bureau_prefetches_prospect_id ON scoring.bureau_prefetches(prospect_id);
CREATE INDEX idx_bureau_prefetches_status ON scoring.bureau_prefetches(status);

-- BPF-02: one active prefetch per prospect
CREATE UNIQUE INDEX uq_bureau_prefetch_prospect_active
    ON scoring.bureau_prefetches(prospect_id)
    WHERE status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED');
