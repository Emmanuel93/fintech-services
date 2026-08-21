--liquibase formatted sql
--changeset accounting:008-order-accounts author:system
--comment Cuentas de orden + regla de alta del préstamo. Registrar el crédito, no sólo su disposición.

-- Hasta aquí la primera huella contable de un crédito era la DISPOSICIÓN del dinero: un crédito
-- autorizado y no dispuesto no existía en ninguna parte de la contabilidad, aunque el compromiso de
-- la institución sí fuera real.
--
-- Se registra en cuentas de orden, que es exactamente para lo que existen. La alternativa —cargar
-- la línea autorizada a cartera vigente— infla el activo con dinero que no salió y descuadra el
-- balance. Las de orden cuadran entre sí y NO forman parte del balance patrimonial.
ALTER TABLE accounting.ledger_accounts DROP CONSTRAINT chk_ledger_accounts_type;
ALTER TABLE accounting.ledger_accounts ADD CONSTRAINT chk_ledger_accounts_type
    CHECK (type IN ('ASSET','CONTRA_ASSET','LIABILITY','INCOME','EXPENSE','EQUITY','ORDER'));

INSERT INTO accounting.ledger_accounts (code, name, type) VALUES
    ('7101', 'Líneas de crédito autorizadas',   'ORDER'),
    ('7201', 'Líneas autorizadas por disponer', 'ORDER');

INSERT INTO accounting.posting_rules (trigger_event, debit_account, credit_account, description) VALUES
    ('ACCOUNT_ACTIVATED', '7101', '7201', 'Alta de línea de crédito autorizada');

-- La estimación preventiva es un CONTRA-ACTIVO: naturaleza acreedora. Tipada como ASSET, su saldo
-- sano sale negativo en la balanza — aritméticamente correcto y contablemente ilegible. El tipo es
-- lo que permite presentarla con el signo que espera un contador sin tocar el dato.
UPDATE accounting.ledger_accounts SET type = 'CONTRA_ASSET' WHERE code = '1290';

-- ── El pago deja de abonarse íntegro a capital ─────────────────────────────
-- `PAYMENT_APPLIED` estaba declarado 1101/1201: el importe completo del pago se abonaba a capital,
-- incluida la parte que liquida interés devengado, que vive en 1203. El efecto acumulado es que
-- 1203 crece indefinidamente y 1201 se subestima — el auxiliar de intereses por cobrar nunca baja
-- aunque los clientes paguen sus intereses.
--
-- A partir de aquí lo postea `PostingService` desglosando el delta de saldos (capital / interés /
-- moratorio) en una póliza de hasta cuatro renglones. La regla se conserva como PLANTILLA para el
-- caso degenerado en que el delta no se pueda desglosar.
UPDATE accounting.posting_rules
   SET description = 'Pago recibido — plantilla; el desglose real lo arma PostingService'
 WHERE trigger_event = 'PAYMENT_APPLIED';
