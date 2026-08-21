--liquibase formatted sql
--changeset collections:004-create-contact-attempts author:system

CREATE TABLE collections.contact_attempts (
    attempt_id    UUID         NOT NULL DEFAULT gen_random_uuid(),
    case_id       UUID         NOT NULL,
    channel       VARCHAR(20)  NOT NULL,
    result        VARCHAR(15)  NOT NULL,
    agent_id      VARCHAR(100) NOT NULL,
    attempted_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_contact_attempts PRIMARY KEY (attempt_id),
    CONSTRAINT fk_contact_attempts_case FOREIGN KEY (case_id)
        REFERENCES collections.collection_cases (case_id),
    CONSTRAINT chk_contact_attempts_result CHECK (result IN
        ('ANSWERED','NO_ANSWER','WRONG_NUMBER','PROMISE_MADE'))
);

CREATE INDEX idx_contact_attempts_case ON collections.contact_attempts (case_id);
-- CT-03: max attempts per day per case (counted in application code; index supports the count query)
CREATE INDEX idx_contact_attempts_case_date ON collections.contact_attempts (case_id, attempted_at);
