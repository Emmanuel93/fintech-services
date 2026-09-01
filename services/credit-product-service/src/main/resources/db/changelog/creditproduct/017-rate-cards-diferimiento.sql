--liquibase formatted sql
--changeset credit-product:017-rate-cards-diferimiento
--comment BK-25b · la tabla de tasas rechazaba un MSI. Se relaja la nominal a >= 0 y se añade `purpose`.

-- **El bloqueador, textual.** `CHECK (nominal_rate > 0 AND nominal_rate < 1)`. Meses sin intereses
-- es tasa cero, así que **un MSI no se podía ni configurar**: la restricción lo rechazaba antes de
-- llegar a ninguna regla de negocio.
--
-- **La moratoria se queda en `> 0`, y no es una omisión.** Una promoción puede no cobrar interés
-- ordinario; si además no cobrara moratorio, diferir sería una forma de dejar de pagar sin
-- consecuencia. Las dos restricciones dicen cosas distintas porque las dos tasas lo son.
ALTER TABLE credit_product.rate_cards
    DROP CONSTRAINT ck_rc_nominal_rate;

ALTER TABLE credit_product.rate_cards
    ADD CONSTRAINT ck_rc_nominal_rate CHECK (nominal_rate >= 0 AND nominal_rate < 1);

-- **Por qué hace falta `purpose`.** La tabla ya resuelve tasas por bandas —`tier_band`,
-- `min/max_amount`, `min/max_term`— con la regla «gana la coincidencia más específica». Su propio
-- comentario da el ejemplo: «SME_LOAN by term band: (1-12, 0.22) | (13-36, 0.26)». Lo que falta es
-- distinguir el propósito, porque **la tasa de diferir no es la tasa de originar el mismo
-- producto**: un producto puede colocarse al 36 % y ofrecer 3 y 6 meses sin intereses.
ALTER TABLE credit_product.rate_cards
    ADD COLUMN purpose VARCHAR(12) NOT NULL DEFAULT 'ORIGINATION'
        CONSTRAINT ck_rc_purpose CHECK (purpose IN ('ORIGINATION', 'DEFERRAL'));

-- `ORIGINATION` como default: es lo que son TODAS las filas existentes. Marcarlas de otro modo
-- cambiaría la tasa con la que se coloca cada producto vivo del catálogo.
COMMENT ON COLUMN credit_product.rate_cards.purpose IS
    'ORIGINATION: tasa al colocar. DEFERRAL: tasa al diferir una compra ya hecha (MSI y promociones).';

-- La resolución busca por (producto, propósito) y desempata por especificidad de banda.
CREATE INDEX idx_rate_cards_purpose
    ON credit_product.rate_cards (product_definition_id, purpose);
