--liquibase formatted sql

--changeset origination:017-bnpl-al-firmar
--comment Los días de BNPL que el cliente pide al firmar el contrato.

-- BNPL es una **decisión del alta**: el cliente dice cuándo empieza a pagar y con eso se corre el
-- plan entero —el devengo y el primer vencimiento a la vez—. Hasta ahora no había dónde guardarlo,
-- así que cartera no podía distinguir «lo pidió» de «no lo pidió» y aplicaba el tope del producto a
-- toda cuenta suya. Como el préstamo personal trae BNPL habilitado, **ninguno empezaba a pagar
-- cuando debía**.
--
-- Vive en el contrato porque es parte de lo que se firma: quien reclame después «yo no pedí empezar
-- a pagar en marzo» tiene aquí la respuesta, con la fecha de firma al lado.
--
-- Nulo es «no pidió ninguno», y es lo que deja el plan empezando en el período siguiente como
-- siempre. No hay default: un default aquí volvería a ser aplicar BNPL a quien no lo pidió.
ALTER TABLE origination.credit_applications
    ADD COLUMN bnpl_deferral_days INTEGER
        CONSTRAINT ck_credit_app_bnpl_days CHECK (bnpl_deferral_days IS NULL OR bnpl_deferral_days > 0);

COMMENT ON COLUMN origination.credit_applications.bnpl_deferral_days IS
    'Días de BNPL pedidos al firmar. NULL = no pidió. El tope lo aplica cartera desde el producto.';
