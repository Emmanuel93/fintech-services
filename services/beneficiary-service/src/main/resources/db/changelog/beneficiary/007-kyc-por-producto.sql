--liquibase formatted sql
--changeset beneficiary:007-kyc-por-producto author:system
--comment F3b: la exigencia de KYC la fija el producto, no una variable de entorno del servicio.

-- ─────────────────────────────────────────────────────────────────────────────
-- Hasta aquí, que una colocación se verificara con proveedor o a mano lo decidía
-- `fintech.beneficiary.identity-verification.mode`: una propiedad del servicio, es decir una
-- decisión de despliegue. Eso tiene dos defectos que no se arreglan cambiándole el nombre.
--
--   1. No se pueden tener dos productos con reglas distintas en el mismo ambiente — y es justo lo
--      que un catálogo necesita: una línea de distribuidor y una tarjeta de uso propio no exigen lo
--      mismo, y hoy comparten bandera.
--   2. Aflojar la verificación acaba siendo un `docker compose up` con otra variable: sin autor,
--      sin fecha y sin quién lo aprobó. Como parte del producto, cambiarla es versionar el
--      producto, y eso queda firmado.
--
-- La política viaja en `Capabilities.kyc` dentro de `product-catalog.product-activated`, el mismo
-- evento que credit-portfolio ya proyecta. Aquí se guarda igual: una proyección local, para no
-- meter una llamada síncrona al catálogo justo en el momento en que se resuelve una identidad.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE beneficiary.product_kyc_policies (
    product_code            VARCHAR(50)  PRIMARY KEY,
    product_version         INTEGER      NOT NULL,
    kyc_required            BOOLEAN      NOT NULL,
    aml_required            BOOLEAN      NOT NULL,
    automation_enabled      BOOLEAN      NOT NULL,
    provider                VARCHAR(30)  NOT NULL DEFAULT 'NONE',
    manual_fallback_allowed BOOLEAN      NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE beneficiary.product_kyc_policies IS
    'Proyección de Capabilities.kyc del catálogo. Fuente de verdad: credit-product. Una fila por '
    'productCode, sobrescrita en cada activación — no interesa el historial, sólo lo vigente.';

COMMENT ON COLUMN beneficiary.product_kyc_policies.automation_enabled IS
    'Si se intenta con proveedor. Apagado NO significa «sin KYC»: significa que lo resuelve una '
    'persona, que es un estado legítimo y distinto de «hay automatización y no sé con quién».';

-- No hay CHECK que exija proveedor cuando hay automatización: el catálogo ya rechaza esa
-- combinación al declararla (`CreditProductCatalogService.validateKycOptions`). Repetirlo aquí
-- convertiría un producto mal configurado en un consumidor caído, y esta proyección no es quien
-- debe frenar el catálogo — sólo reflejarlo.

-- ─────────────────────────────────────────────────────────────────────────────
-- De qué producto es cada colocación.
--
-- Se guarda y no se vuelve a preguntar. Dos razones: la política que se aplicó tiene que ser
-- reconstruible meses después —ante la beneficiaria y ante una revisión— y resolver la identidad no
-- puede depender de que credit-portfolio conteste en ese instante.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE beneficiary.placements
    ADD COLUMN product_code VARCHAR(50);

COMMENT ON COLUMN beneficiary.placements.product_code IS
    'Producto de la línea del distribuidor al crear la colocación. NULL en las anteriores a F3b: '
    'sin él no se sabe qué política aplicaba, y el resolvedor cae al default seguro (KYC manual).';

CREATE INDEX idx_placements_product_code
    ON beneficiary.placements (product_code)
    WHERE product_code IS NOT NULL;
