--liquibase formatted sql
--changeset creditportfolio:018-iva-en-cuenta-y-calendario author:system
--comment El IVA deja de esconderse dentro del saldo de penalización y aparece en el calendario.

-- **En la cuenta.** El IVA trasladado se estaba acumulando en `penalty_balance`, junto con los
-- moratorios y las comisiones, porque el reconciliador manda ahí todo lo que no es interés
-- ordinario. Eso hace impresentable el saldo: «Penalización $4,200» en un crédito al corriente son
-- en realidad impuestos, y no hay forma de saber cuánto del adeudo es del SAT y cuánto es mora del
-- cliente. Son tres conceptos con dueño distinto y tienen que poder leerse por separado.
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN IF NOT EXISTS iva_balance NUMERIC(15,2) NOT NULL DEFAULT 0;

-- **En el calendario.** Una cuota se cobra con IVA sobre su interés, y el plan sólo mostraba capital
-- e interés: el total de la cuota no cuadraba contra lo que de verdad se le cobra al cliente, y la
-- diferencia —justo el impuesto— parecía un error de redondeo repartido por todo el plan.
ALTER TABLE credit_portfolio.installments
    ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(15,2) NOT NULL DEFAULT 0;

-- La tasa que se le aplicó al colocar, congelada.
--
-- No se lee de sales-org en cada devengo: la tasa vigente el día que se colocó rige la vida entera
-- del crédito, y una reforma fiscal posterior no puede reescribir el plan de pagos que el cliente
-- firmó. Es el mismo principio por el que la sucursal de origen se sella y no cambia.
ALTER TABLE credit_portfolio.credit_accounts
    ADD COLUMN IF NOT EXISTS vat_rate NUMERIC(6,4);
