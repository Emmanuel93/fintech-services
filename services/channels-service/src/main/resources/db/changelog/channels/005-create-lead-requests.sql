--liquibase formatted sql
--changeset channels:005-create-lead-requests

CREATE TABLE channels.lead_requests (
    lead_id             UUID         NOT NULL PRIMARY KEY,
    channel_id          UUID         NOT NULL REFERENCES channels.channels (channel_id),
    intent_type         VARCHAR(50)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    first_name          VARCHAR(100) NOT NULL,
    last_name1          VARCHAR(100),
    phone               VARCHAR(20),
    email               VARCHAR(200),
    promoter_party_id   UUID,
    converted_party_id  UUID,
    expires_at          TIMESTAMPTZ  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_leads_promoter ON channels.lead_requests (promoter_party_id)
    WHERE promoter_party_id IS NOT NULL;
CREATE INDEX idx_leads_status ON channels.lead_requests (status);
CREATE INDEX idx_leads_expires_at ON channels.lead_requests (expires_at);
