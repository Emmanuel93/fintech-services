--liquibase formatted sql
--changeset collections:006-create-collection-agreements author:system

CREATE TABLE collections.collection_agreements (
    agreement_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    case_id            UUID         NOT NULL,
    credit_account_id  UUID         NOT NULL,
    obligor_party_id   UUID         NOT NULL,
    type               VARCHAR(15)  NOT NULL,
    status             VARCHAR(10)  NOT NULL DEFAULT 'PROPOSED',
    original_debt      NUMERIC(19,4) NOT NULL,
    forgiven_amount    NUMERIC(19,4),
    new_terms          JSONB,
    authorized_by      VARCHAR(100),
    authorization_ref  VARCHAR(100),
    proposed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    responded_at       TIMESTAMPTZ,
    executed_at        TIMESTAMPTZ,
    bureau_reported    BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_collection_agreements PRIMARY KEY (agreement_id),
    CONSTRAINT fk_collection_agreements_case FOREIGN KEY (case_id)
        REFERENCES collections.collection_cases (case_id),
    CONSTRAINT chk_collection_agreements_type CHECK (type IN ('RESTRUCTURE','QUITA_PARCIAL')),
    CONSTRAINT chk_collection_agreements_status CHECK (status IN
        ('PROPOSED','ACCEPTED','EXECUTED','REJECTED','EXPIRED'))
);

CREATE INDEX idx_collection_agreements_case ON collections.collection_agreements (case_id);
CREATE INDEX idx_collection_agreements_account ON collections.collection_agreements (credit_account_id);
-- AG-01: only one PROPOSED/ACCEPTED agreement per case at a time
CREATE UNIQUE INDEX idx_collection_agreements_active_per_case
    ON collections.collection_agreements (case_id)
    WHERE status IN ('PROPOSED', 'ACCEPTED');
