--liquibase formatted sql
--changeset credit-portfolio:052-pago-directo-del-cliente
--comment A distributor's client paying his own loans: the payment entry keeps whose money it was.

-- A deposit to her CLABE with his reference (payments' allocation BENEFICIARY) is her line's payment like any other,
-- and it says it was his: his installments already billed are covered first, even when her statement is not complete,
-- and her statement names him in its movements.
ALTER TABLE credit_portfolio.balance_events ADD COLUMN beneficiary_party_id UUID;
CREATE INDEX idx_balance_events_beneficiary ON credit_portfolio.balance_events (credit_account_id, beneficiary_party_id)
    WHERE beneficiary_party_id IS NOT NULL;

COMMENT ON COLUMN credit_portfolio.balance_events.beneficiary_party_id IS
    'Cliente de la distribuidora que hizo este pago con su propia referencia; nulo en cualquier otro asiento';
