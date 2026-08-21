CREATE TABLE configuration.config_parameters (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    param_key            VARCHAR(100) NOT NULL,
    value                TEXT         NOT NULL,
    product_type         VARCHAR(50),
    channel_type         VARCHAR(50),
    version              INT          NOT NULL DEFAULT 1,
    status               VARCHAR(30)  NOT NULL,
    effective_date       DATE,
    created_by           UUID         NOT NULL,
    approved_by          UUID,
    previous_version_ref UUID,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_config_parameters PRIMARY KEY (id),
    CONSTRAINT chk_config_parameters_status CHECK (
        status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'DEPRECATED')
    ),
    CONSTRAINT chk_config_parameters_version CHECK (version >= 1)
);

-- Only one ACTIVE parameter per (key, productType, channelType) triple
CREATE UNIQUE INDEX idx_config_parameters_active_key
    ON configuration.config_parameters (param_key, COALESCE(product_type, ''), COALESCE(channel_type, ''))
    WHERE status = 'ACTIVE';

CREATE INDEX idx_config_parameters_key_status
    ON configuration.config_parameters (param_key, status);

CREATE INDEX idx_config_parameters_pending
    ON configuration.config_parameters (status)
    WHERE status = 'PENDING_APPROVAL';
