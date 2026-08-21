--liquibase formatted sql
--changeset accounting:010-writeoff-derecognition author:system
--comment IFRS 9 §5.4.4: castigar es dar de baja el activo, y lo castigado se sigue en cuentas de orden.

-- **La brecha que esto cierra.** `1210 Cartera de crédito castigada` existía en el catálogo desde el
-- primer seed y NINGUNA regla la usaba: el quebranto abonaba directo a `1201`, así que lo castigado
-- desaparecía del mayor y su seguimiento quedaba en cobranza. IFRS 9 pide justo lo contrario: el
-- activo se da de baja del balance, pero la institución **sigue debiendo poder decir cuánto castigó
-- y cuánto recuperó de ello** — el castigo no extingue el derecho de cobro.
--
-- La contrapartida es de ORDEN y no de balance: lo castigado ya no es un activo. Registrarlo en
-- `1210` como activo lo devolvería al balance por la puerta de atrás, que es exactamente lo que la
-- baja pretende evitar.
ALTER TABLE accounting.ledger_accounts DROP CONSTRAINT chk_ledger_accounts_type;
ALTER TABLE accounting.ledger_accounts ADD CONSTRAINT chk_ledger_accounts_type
    CHECK (type IN ('ASSET','CONTRA_ASSET','LIABILITY','INCOME','EXPENSE','EQUITY','ORDER'));

-- `1210` deja de ser activo: es la memoria de lo castigado, no un derecho reconocido.
UPDATE accounting.ledger_accounts SET type = 'ORDER' WHERE code = '1210';

INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('7301', 'Control de cartera castigada', 'ORDER');
