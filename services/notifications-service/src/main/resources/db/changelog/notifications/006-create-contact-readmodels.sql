--liquibase formatted sql
--changeset notifications:006-create-contact-readmodels author:system

-- Prerequisito de contacto (ver T2_notifications.md §Prerequisito crítico) — 3 pasos, no 5.
CREATE TABLE notifications.prospect_contact_shadow (
    prospect_id UUID        NOT NULL,
    first_name  VARCHAR(100),
    phone       VARCHAR(20),
    email       VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_prospect_contact_shadow PRIMARY KEY (prospect_id)
);

CREATE TABLE notifications.application_prospect_link (
    application_id UUID        NOT NULL,
    prospect_id    UUID        NOT NULL,
    offered_term   INT,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_application_prospect_link PRIMARY KEY (application_id)
);

CREATE TABLE notifications.party_contact_directory (
    party_id    UUID        NOT NULL,
    first_name  VARCHAR(100),
    phone       VARCHAR(20),
    email       VARCHAR(200),
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_party_contact_directory PRIMARY KEY (party_id)
);
