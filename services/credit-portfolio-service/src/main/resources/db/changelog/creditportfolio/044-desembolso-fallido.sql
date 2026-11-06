--liquibase formatted sql
--changeset credit-portfolio:044-desembolso-fallido
--comment A disbursement that fails is retried; one that cannot happen is compensated at once (D63).

-- Each attempt of a disposition's payment is its own order. The disposition keeps which attempt is in flight, why the
-- last one failed, and to whom the money goes: every attempt sends the same instruction, and the file says it.
ALTER TABLE credit_portfolio.dispositions
    ADD COLUMN attempt            INTEGER       NOT NULL DEFAULT 1,
    ADD COLUMN failure_code       VARCHAR(60),
    ADD COLUMN failure_reason     VARCHAR(1000),
    ADD COLUMN payee_name         VARCHAR(255),
    ADD COLUMN payee_account      VARCHAR(20),
    ADD COLUMN payee_account_type VARCHAR(4),
    ADD COLUMN payee_tax_id       VARCHAR(18),
    ADD COLUMN payee_concept      VARCHAR(40);

COMMENT ON COLUMN credit_portfolio.dispositions.attempt IS
    'The attempt of this payment in flight (or the last one): each is its own disbursement order, keyed <id>:<attempt>';
COMMENT ON COLUMN credit_portfolio.dispositions.failure_code IS
    'Why the last attempt failed, as disbursement says it (its FailureCode): decides retry or compensation';

-- An installment credit whose only disbursement could not happen is cancelled: it never lent anything.
ALTER TABLE credit_portfolio.credit_accounts DROP CONSTRAINT IF EXISTS ck_credit_account_status;
ALTER TABLE credit_portfolio.credit_accounts ADD CONSTRAINT ck_credit_account_status CHECK (status IN (
    'PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'RESTRUCTURED', 'SETTLED', 'WRITTEN_OFF', 'CLOSED', 'CANCELLED'));
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN cancellation_reason VARCHAR(60),
    ADD COLUMN cancelled_at        TIMESTAMPTZ;

-- Its plan is cancelled with it: kept for the record, it owes nothing.
ALTER TABLE credit_portfolio.installments DROP CONSTRAINT IF EXISTS ck_installment_status;
ALTER TABLE credit_portfolio.installments ADD CONSTRAINT ck_installment_status
    CHECK (status IN ('PENDING', 'PAID', 'OVERDUE', 'PARTIAL', 'REPLACED', 'CANCELLED'));
