--liquibase formatted sql
--changeset credit-portfolio:049-cierre-de-linea-tras-quita
--comment A line closed for a reason: when and why (QUITA: collections forgave part of its debt and the holder settled the rest).

-- A quita is the distributor's exit ("no vuelve a ingresar: se va y se cierra la línea"): from that day her line
-- places no more, and once she settles what is left the account closes (CLOSED). The reason and the day are kept
-- next to the status, as a cancelled credit keeps its own (cancellation_reason, cancelled_at).
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN closure_reason VARCHAR(60),
    ADD COLUMN closed_at TIMESTAMPTZ;

COMMENT ON COLUMN credit_portfolio.credit_accounts.closure_reason IS
    'Por qué se cerró la línea: QUITA = cobranza condonó parte del adeudo y la titular liquidó el resto';
COMMENT ON COLUMN credit_portfolio.credit_accounts.closed_at IS 'Cuándo se cerró la línea';
