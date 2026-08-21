-- liquibase formatted sql

-- changeset identity:004 author:fintech
-- comment: Per-client IP/CIDR whitelist for external system authentication
CREATE TABLE identity.client_ip_whitelist (
    id         UUID PRIMARY KEY,
    client_id  UUID         NOT NULL REFERENCES identity.clients(id) ON DELETE CASCADE,
    cidr       VARCHAR(50)  NOT NULL,
    label      VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (client_id, cidr)
);

CREATE INDEX idx_client_ip_whitelist_client_id ON identity.client_ip_whitelist(client_id);
