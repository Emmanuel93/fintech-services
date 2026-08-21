-- liquibase formatted sql

-- changeset identity:003 author:fintech
-- comment: Client/secret authentication for external expert systems
CREATE TABLE identity.clients (
    id          UUID PRIMARY KEY,
    client_id   VARCHAR(100) NOT NULL UNIQUE,
    secret_hash VARCHAR(255) NOT NULL,
    client_name VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    roles       TEXT         NOT NULL DEFAULT '',
    expires_at  TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_clients_status ON identity.clients(status);
