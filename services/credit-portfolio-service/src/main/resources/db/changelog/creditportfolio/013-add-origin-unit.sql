--liquibase formatted sql
--changeset creditportfolio:013-add-origin-unit author:system
--comment La sucursal que colocó el crédito, sellada al activarlo. No cambia aunque la cartera se reasigne.

-- Hasta aquí no existía en ninguna tabla del sistema la sucursal de un préstamo, así que la
-- contabilidad no se podía atribuir a una rama: «pólizas por sucursal» no era un dato mal expuesto,
-- era un dato que no estaba.
--
-- **Se sella una vez y no se recalcula.** La alternativa —derivarla del ejecutivo que lleva al
-- cliente hoy— es tentadora porque el dato ya existe en party, y es exactamente lo que no se puede
-- hacer: la cartera se reasigna, y mover 400 clientes de una sucursal a otra reescribiría
-- retroactivamente la contabilidad de los meses anteriores. La balanza de marzo daría otro número en
-- agosto y los estados financieros dejarían de ser reproducibles.
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN origin_unit_code VARCHAR(40);

CREATE INDEX idx_credit_accounts_origin_unit
    ON credit_portfolio.credit_accounts (origin_unit_code)
    WHERE origin_unit_code IS NOT NULL;

-- Los créditos que ya existen quedan en NULL a propósito. Se sellan con el script de reconciliación
-- (resolviendo ejecutivo → sucursal contra sales-org, una vez), y lo que no se pueda resolver queda
-- visible y contado bajo «Sin sucursal» en vez de repartirse con una heurística que nadie podrá
-- auditar después.
