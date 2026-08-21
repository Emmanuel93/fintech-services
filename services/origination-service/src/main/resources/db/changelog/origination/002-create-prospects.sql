-- D3 Origination — prospects table (prospect intake aggregate)
CREATE TABLE origination.prospects (
    prospect_id             UUID        NOT NULL,
    prospect_type           VARCHAR(20) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'CAPTURED',

    -- Personal identity
    first_name              VARCHAR(100) NOT NULL,
    last_name1              VARCHAR(100) NOT NULL,
    last_name2              VARCHAR(100),
    curp                    VARCHAR(18)  NOT NULL,
    rfc                     VARCHAR(13),
    date_of_birth           DATE         NOT NULL,
    gender                  VARCHAR(10)  NOT NULL,
    state_of_birth          VARCHAR(100) NOT NULL,

    -- Contact
    phone                   VARCHAR(20)  NOT NULL,
    email                   VARCHAR(254),

    -- Address (embedded value object)
    street                  VARCHAR(200),
    exterior_number         VARCHAR(20),
    interior_number         VARCHAR(20),
    neighborhood            VARCHAR(100),
    municipality            VARCHAR(100),
    city                    VARCHAR(100),
    state                   VARCHAR(100),
    postal_code             VARCHAR(10),
    country                 VARCHAR(2)   NOT NULL DEFAULT 'MX',

    -- Channel and intent
    channel_type            VARCHAR(30)  NOT NULL,
    product_type_intent     VARCHAR(30),

    -- Privacy consent (LFPDPPP Art. 9) — mandatory
    privacy_notice_accepted     BOOLEAN     NOT NULL DEFAULT FALSE,
    privacy_notice_accepted_at  TIMESTAMPTZ NOT NULL,

    -- Círculo de Crédito consent (optional — scoring skips CDC query when false)
    circulo_consent_accepted     BOOLEAN     NOT NULL DEFAULT FALSE,
    circulo_consent_accepted_at  TIMESTAMPTZ,

    -- Timestamps
    created_at              TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_prospects          PRIMARY KEY (prospect_id),
    CONSTRAINT uq_prospects_curp     UNIQUE (curp),
    CONSTRAINT uq_prospects_phone    UNIQUE (phone),
    CONSTRAINT ck_prospects_status   CHECK (status IN ('CAPTURED','SUBMITTED','CONVERTED','EXPIRED')),
    CONSTRAINT ck_prospect_type      CHECK (prospect_type IN ('INDIVIDUAL','BUSINESS')),
    CONSTRAINT ck_privacy_required   CHECK (privacy_notice_accepted = TRUE)
);

CREATE INDEX idx_prospects_status      ON origination.prospects (status);
CREATE INDEX idx_prospects_created_at  ON origination.prospects (created_at);
CREATE INDEX idx_prospects_expires_at  ON origination.prospects (expires_at) WHERE status = 'CAPTURED';
