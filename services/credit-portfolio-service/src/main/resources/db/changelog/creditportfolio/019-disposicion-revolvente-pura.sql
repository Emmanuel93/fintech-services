--liquibase formatted sql
--changeset creditportfolio:019-disposicion-revolvente-pura
--comment BK-24 · una compra de tarjeta nace SIN calendario. El modelo estaba invertido.

-- **El modelo invertido.** Hoy TODA disposición nace con su calendario de amortización, también la
-- de uso propio. El comentario del código lo justifica: «una revolvente no tiene un plazo; lo tiene
-- cada disposición».
--
-- Eso es correcto **para el distribuidor**, donde el vendedor decide «a cuántos meses se lo dejas»
-- en el momento de colocar. **Para una tarjeta es al revés:** la compra nace revolvente pura
-- —exigible completa en la siguiente fecha de pago posterior al corte— y el titular decide
-- diferirla después, antes de que corte.
--
-- Con el modelo actual una compra con tarjeta ya nace parcializada al plazo por defecto del
-- producto, que no es lo que el cliente pidió ni lo que el corte debería exigirle.
ALTER TABLE credit_portfolio.dispositions
    ADD COLUMN plan_mode VARCHAR(12) NOT NULL DEFAULT 'AMORTIZED'
        CONSTRAINT dispositions_plan_mode_chk CHECK (plan_mode IN ('REVOLVING', 'AMORTIZED')),
    ADD COLUMN deferred_at TIMESTAMPTZ,
    ADD COLUMN deferred_term INTEGER,
    -- En qué ciclo de corte se facturó esta compra. Sin esta marca, el corte siguiente volvería a
    -- exigir las mismas compras: `plan_mode` sigue siendo REVOLVING después de facturarla.
    ADD COLUMN billed_cycle INTEGER;

-- `AMORTIZED` como default es deliberado: es lo que TODAS las disposiciones existentes son. Marcar
-- las viejas como revolventes las volvería exigibles de golpe en el siguiente corte.
COMMENT ON COLUMN credit_portfolio.dispositions.plan_mode IS
    'REVOLVING: sin calendario, exigible completa en el corte. AMORTIZED: tiene plan propio (BK-24).';
COMMENT ON COLUMN credit_portfolio.dispositions.deferred_at IS
    'Cuándo el titular difirió esta compra. NULL = nació amortizada o sigue revolvente.';

-- El exigible del corte se arma sumando las revolventes del ciclo: sin este índice la consulta
-- recorrería todas las disposiciones históricas de la línea en cada corte.
-- El exigible del corte se arma sumando las revolventes AÚN NO FACTURADAS: sin este índice la
-- consulta recorrería todas las disposiciones históricas de la línea en cada corte.
CREATE INDEX idx_dispositions_por_facturar
    ON credit_portfolio.dispositions (credit_account_id, created_at)
    WHERE plan_mode = 'REVOLVING' AND billed_cycle IS NULL;
