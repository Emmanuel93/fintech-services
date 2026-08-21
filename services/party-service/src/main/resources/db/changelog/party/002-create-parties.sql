CREATE TABLE party.parties
(
    party_id             UUID         NOT NULL,
    prospect_id          UUID         NOT NULL,
    evaluation_id        UUID,                              -- poblado cuando scoring complete
    party_type           VARCHAR(20)  NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'PROSPECT',
    first_name           VARCHAR(100),
    last_name1           VARCHAR(100),
    last_name2           VARCHAR(100),
    curp                 VARCHAR(18),
    rfc                  VARCHAR(13),
    date_of_birth        DATE,
    risk_level           VARCHAR(10),                      -- poblado cuando scoring complete
    total_score          INTEGER,                          -- poblado cuando scoring complete
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_parties             PRIMARY KEY (party_id),
    CONSTRAINT uq_parties_prospect_id UNIQUE (prospect_id),
    CONSTRAINT uq_parties_curp        UNIQUE (curp),
    CONSTRAINT ck_party_type          CHECK (party_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT ck_party_status        CHECK (status IN ('PROSPECT', 'ACTIVE', 'SUSPENDED', 'BLACKLISTED', 'CLOSED')),
    CONSTRAINT ck_risk_level          CHECK (risk_level IS NULL OR risk_level IN ('BAJO', 'MEDIO', 'ALTO'))
);

CREATE INDEX idx_parties_created_at ON party.parties (created_at DESC);
CREATE INDEX idx_parties_curp       ON party.parties (curp) WHERE curp IS NOT NULL;
