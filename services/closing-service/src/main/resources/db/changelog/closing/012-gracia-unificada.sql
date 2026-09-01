--liquibase formatted sql
--changeset closing:012-gracia-unificada
--comment BK-21 · una sola gracia. El DEFAULT de la columna decía 0 y todo lo demás decía 3.

-- **Lo que había, con precisión.** El análisis previo (AN-17) dio por hecho que las dos fuentes de
-- la gracia tenían valores distintos. Al revisarlo, la divergencia es más estrecha y más traicionera:
--
--   · `charges` usa 3 días, en propiedades y en el esquema.
--   · El SEED de políticas de este servicio ya inserta 3, y su comentario lo dice.
--   · Pero el DEFAULT de la columna es 0.
--
-- Es decir: mientras alguien inserte políticas por el seed, coinciden. La primera política dada de
-- alta **sin especificar la columna** —desde el backoffice, desde un script— arranca con gracia
-- cero, y esa cuenta cae en mora el día siguiente al vencimiento mientras sus vecinas tienen tres
-- días. Un default que sólo se equivoca cuando nadie mira es peor que uno que se equivoca siempre.
ALTER TABLE closing.close_cycle_policies
    ALTER COLUMN payment_due_offset_days SET DEFAULT 3;

COMMENT ON COLUMN closing.close_cycle_policies.payment_due_offset_days IS
    'Días de gracia tras la fecha exigible. Única fuente junto con charges.accrual_schedules.grace_period_days, que tiene el mismo default (BK-21).';
