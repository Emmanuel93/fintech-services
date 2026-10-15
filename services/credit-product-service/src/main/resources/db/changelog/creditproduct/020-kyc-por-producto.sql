--liquibase formatted sql
--changeset credit-product:020-kyc-por-producto
--comment F3b · cada producto declara qué verificación de identidad exige y cómo se resuelve.

-- La exigencia de KYC era `BENEFICIARY_IDENTITY_VERIFICATION_MODE`: una variable de entorno, es
-- decir una decisión de despliegue. Eso obligaba a que todos los productos de un ambiente
-- compartieran regla —y una línea de distribuidor y una tarjeta de uso propio no exigen lo mismo—
-- y convertía aflojar la verificación en un redeploy sin autor ni fecha. Aquí es parte del
-- producto: cambiarla es versionarlo, y queda firmado.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- Por qué se declara aunque el default ya sea correcto
-- ─────────────────────────────────────────────────────────────────────────────
-- `KycOptions.orDefault(null)` devuelve «exigido y manual», así que un producto sin bloque ya se
-- comporta bien. Se declara igual por dos razones: que el catálogo **diga** su política en vez de
-- heredarla —quien lo audite no debería tener que conocer el default de una clase Java— y que el
-- consumidor deje de registrar en cada arranque que el producto no la declara.
--
-- ─────────────────────────────────────────────────────────────────────────────
-- Todos exigen KYC, y hoy todos lo resuelven a mano
-- ─────────────────────────────────────────────────────────────────────────────
-- Quien queda registrado pasa por el mismo KYC/AML, sea la beneficiaria de una línea de
-- distribuidor o el titular de un producto de uso propio: es un solo proceso con configuración por
-- producto, no dos procesos paralelos que se desincronizan.
--
-- `automationEnabled=false` en todos porque **no hay contrato con proveedor de KYC**. Encenderlo
-- aquí declararía una automatización que no existe: el catálogo rechaza `automationEnabled=true`
-- sin proveedor, y con proveedor declarado pero sin adaptador todo caería igual a manual, sólo que
-- diciendo que no. El día que exista Incode, esto es un UPDATE por producto — no un redeploy.

UPDATE credit_product.credit_product_definitions
   SET capabilities = capabilities || '{
        "kyc": {
            "kycRequired": true,
            "amlRequired": true,
            "automationEnabled": false,
            "provider": "NONE",
            "manualFallbackAllowed": true
        }}'::jsonb
 WHERE capabilities IS NOT NULL
   -- `jsonb_exists(...)` y no el operador `?`: el signo de interrogación es el placeholder de
   -- JDBC, y el driver lo convierte en un parámetro antes de que Postgres lo vea. Falla con
   -- «syntax error at or near $1», que no se parece en nada a la causa.
   AND NOT jsonb_exists(capabilities, 'kyc');

-- Comprobación: ningún producto puede quedarse sin declararlo. Si alguno se cuela, el arranque
-- falla aquí y no seis pasos más adelante con una beneficiaria esperando.
DO $$
DECLARE faltantes INT;
BEGIN
    SELECT COUNT(*) INTO faltantes
      FROM credit_product.credit_product_definitions
     WHERE capabilities IS NULL OR NOT jsonb_exists(capabilities, 'kyc');

    IF faltantes > 0 THEN
        RAISE EXCEPTION 'F3b: % producto(s) sin política de KYC declarada', faltantes;
    END IF;
END $$;
