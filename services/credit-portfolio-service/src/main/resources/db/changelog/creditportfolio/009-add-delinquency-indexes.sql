CREATE INDEX IF NOT EXISTS idx_credit_accounts_status
    ON credit_portfolio.credit_accounts (status);

CREATE INDEX IF NOT EXISTS idx_installments_due_date_status
    ON credit_portfolio.installments (due_date, status);
