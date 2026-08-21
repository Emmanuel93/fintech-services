CREATE TABLE party.party_relationships
(
    relationship_id   UUID         NOT NULL,
    party_id          UUID         NOT NULL,
    related_party_id  UUID         NOT NULL,
    relationship_type VARCHAR(50)  NOT NULL,
    credit_product_id UUID,
    active            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at          TIMESTAMPTZ,

    CONSTRAINT pk_party_relationships   PRIMARY KEY (relationship_id),
    CONSTRAINT fk_rel_party             FOREIGN KEY (party_id)         REFERENCES party.parties (party_id),
    CONSTRAINT fk_rel_related_party     FOREIGN KEY (related_party_id) REFERENCES party.parties (party_id),
    CONSTRAINT ck_rel_type              CHECK (relationship_type IN ('GUARANTOR', 'BENEFICIARY', 'LEGAL_REPRESENTATIVE', 'DISTRIBUTOR', 'COSIGNER')),
    CONSTRAINT ck_rel_no_self           CHECK (party_id <> related_party_id)
);

CREATE INDEX idx_rel_party_id         ON party.party_relationships (party_id);
CREATE INDEX idx_rel_related_party_id ON party.party_relationships (related_party_id);
CREATE INDEX idx_rel_active           ON party.party_relationships (party_id, active) WHERE active = TRUE;
