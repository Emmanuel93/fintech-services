--liquibase formatted sql
--changeset sales-org:008-iva-por-unidad author:system
--comment El IVA depende de dónde se coloca el crédito, no del servicio que lo calcula.

-- México aplica un estímulo fiscal en la Región Fronteriza Norte y Sur que baja el IVA del 16% al
-- 8%. Una sucursal de Tijuana y una de Guadalajara trasladan tasas distintas sobre el mismo
-- producto, así que la tasa no puede vivir en la configuración del servicio: ahí obligaría a
-- desplegar una instancia por zona.
--
-- Va en la unidad y **se hereda del padre**: se declara una vez en la región fronteriza y todas sus
-- zonas y sucursales la toman. Declararla sucursal por sucursal garantiza que la próxima que se
-- abra se quede con la tasa equivocada, porque nadie recuerda que hay que ponérsela.
ALTER TABLE sales_org.org_units
    ADD COLUMN IF NOT EXISTS vat_rate NUMERIC(6,4);

COMMENT ON COLUMN sales_org.org_units.vat_rate IS
    'IVA a trasladar en esta unidad y su subárbol. NULL hereda del padre; sin ancestro con tasa, manda el nacional.';

-- El nacional se fija aquí porque la raíz MX sí existe a esta altura: la crea la escalera por
-- defecto (005). Las regiones no —las siembra `seed-sales-org.py` después—, así que su tasa se
-- declara allá. Sembrar en una migración sólo alcanza a las filas que ya existen, y un UPDATE que
-- no encuentra nada no falla: deja la tasa sin poner y no avisa.
UPDATE sales_org.org_units SET vat_rate = 0.1600 WHERE code = 'MX';
