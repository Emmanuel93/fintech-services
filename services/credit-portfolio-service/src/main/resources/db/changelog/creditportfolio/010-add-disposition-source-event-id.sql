ALTER TABLE credit_portfolio.dispositions
    ADD COLUMN source_event_id VARCHAR(100);

CREATE UNIQUE INDEX uq_dispositions_source_event_id
    ON credit_portfolio.dispositions (source_event_id)
    WHERE source_event_id IS NOT NULL;
