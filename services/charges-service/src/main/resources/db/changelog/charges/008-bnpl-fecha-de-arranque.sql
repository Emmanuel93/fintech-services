--liquibase formatted sql
--changeset charges:008-bnpl-fecha-de-arranque
--comment BK-28 · buy now pay later: el devengo no arranca el día uno.

-- **Lo que no existía.** `needsAccrual` devenga desde el momento en que se crea el calendario. No
-- había forma de decir «este crédito empieza a devengar el 15 de septiembre»: un producto BNPL
-- cobraba interés desde el primer día, que es exactamente lo contrario de lo que promete.
--
-- NULL significa «desde siempre», que es lo que son todos los créditos existentes. Un default con
-- fecha los pondría a todos a arrancar el día del despliegue.
ALTER TABLE charges.accrual_schedules
    ADD COLUMN accrual_start_date DATE;

COMMENT ON COLUMN charges.accrual_schedules.accrual_start_date IS
    'Desde cuándo devenga interés ordinario. NULL = desde el alta. La fija BNPL al originar (BK-28).';
