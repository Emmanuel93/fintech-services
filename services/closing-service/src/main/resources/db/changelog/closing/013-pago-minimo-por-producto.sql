--liquibase formatted sql
--changeset closing:013-pago-minimo-por-producto author:system
--comment El porcentaje de pago mínimo, por producto y versionado. Era una constante del 5 % en Java.

-- ─────────────────────────────────────────────────────────────────────────────
-- Era esto, en `CutoffPhaseProcessor`:
--
--     private static final BigDecimal MINIMUM_PAYMENT_RATE = new BigDecimal("0.05");
--
-- Con lo que el pago mínimo era **el mismo 5 % para todos los productos**, y cambiarlo para uno
-- exigía un despliegue. En un producto revolvente real el mínimo es un parámetro comercial: una
-- tarjeta de nómina y una de consumo no exigen lo mismo, y cambia por campaña.
--
-- La infraestructura para configurarlo ya existía y estaba sin usar: esta tabla tiene `scope_type`
-- (GLOBAL · PRODUCT_TYPE · PRODUCT), `version`, `status` y `effective_date` — o sea, política por
-- producto, versionada y con vigencia. Le faltaba la columna.
--
-- NULL significa «este producto no exige pago mínimo», que es lo correcto para un producto a plazo:
-- su exigible sale del plan de amortización, no de un porcentaje del saldo.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE closing.close_cycle_policies
    ADD COLUMN minimum_payment_rate NUMERIC(6,4);

COMMENT ON COLUMN closing.close_cycle_policies.minimum_payment_rate IS
    'Fracción del saldo al corte exigida como pago mínimo en un revolvente (0.05 = 5 %). '
    'NULL = el producto no exige mínimo: un producto a plazo saca su exigible del plan.';

-- Un porcentaje fuera de (0,1] no es una configuración estricta ni laxa: es un error de captura que
-- produciría un mínimo de cero —que no exige nada— o mayor que el saldo —que nadie puede pagar—.
ALTER TABLE closing.close_cycle_policies
    ADD CONSTRAINT chk_ccp_minimum_payment_rate
        CHECK (minimum_payment_rate IS NULL
               OR (minimum_payment_rate > 0 AND minimum_payment_rate <= 1));

-- Las políticas que ya existen conservan el 5 % que el código aplicaba, para que este changeset
-- **no cambie ningún importe**: lo que cambia es de dónde sale el número, no cuál es.
UPDATE closing.close_cycle_policies
   SET minimum_payment_rate = 0.0500
 WHERE minimum_payment_rate IS NULL;
