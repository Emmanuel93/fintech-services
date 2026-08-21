CREATE TABLE configuration.config_audit_trail (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    parameter_id UUID        NOT NULL,
    action       VARCHAR(30) NOT NULL,
    actor_id     UUID        NOT NULL,
    timestamp    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    old_value    TEXT,
    new_value    TEXT,

    CONSTRAINT pk_config_audit_trail PRIMARY KEY (id),
    CONSTRAINT fk_audit_parameter FOREIGN KEY (parameter_id)
        REFERENCES configuration.config_parameters (id),
    CONSTRAINT chk_audit_action CHECK (
        action IN ('CREATED', 'APPROVED', 'REJECTED', 'DEPRECATED')
    )
);

CREATE INDEX idx_audit_parameter_id ON configuration.config_audit_trail (parameter_id);
CREATE INDEX idx_audit_timestamp    ON configuration.config_audit_trail (timestamp DESC);
