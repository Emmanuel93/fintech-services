--liquibase formatted sql
--changeset accounting:014-interes-etapa-3-neto
--comment Stage 3 interest on a net basis (IFRS 9 §5.4.1(b)): the stage and exposure risk measured, and the memo accounts of the interest not recognized.

-- A credit-impaired loan keeps accruing its contractual interest, but its interest revenue is the effective rate on
-- the amortised cost (gross carrying amount less the loss allowance), not on the gross amount. To split each charge,
-- accounting needs what risk measured for the account: its stage and its exposure. Risk already publishes both with
-- every assessment; until now only the reserve amount was kept.
ALTER TABLE accounting.provision_ledger
    ADD COLUMN IF NOT EXISTS ifrs9_stage VARCHAR(10),
    ADD COLUMN IF NOT EXISTS ead         NUMERIC(19, 2);

-- The contractual interest of a Stage 3 loan that was not recognized as revenue, as a memo: it is still owed, and
-- it is what the institution would collect if the loan cured. It does not move the balance sheet (that part went to
-- the loss allowance, 1290); it keeps the record of how much revenue was held back.
INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('7401', 'Intereses de cartera en etapa 3 no reconocidos', 'ORDER'),
    ('7402', 'Contracuenta de intereses de etapa 3 no reconocidos', 'ORDER')
ON CONFLICT (code) DO NOTHING;
