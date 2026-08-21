-- Exigir que exista puntaje de buró.
--
-- Sin esta regla, un solicitante del que el buró no trae score salía AUTO_APPROVED.
--
-- No era una decisión: era un efecto de haber ampliado el motor. `FICO_THRESHOLD` compara contra un
-- umbral, y un valor nulo no cumple ningún operador, así que «sin score» no restaba nada — sumaba
-- cero y seguía adelante. Mientras las reglas miraban casi sólo al buró eso se notaba, porque sin
-- buró no había con qué sumar. Al incorporar ingreso, antigüedad laboral y edad, esas reglas solas
-- ya alcanzaban el umbral de aprobación, y quien no tenía historial pasaba sin que nadie lo hubiera
-- decidido.
--
-- `FICO_SCORES_COUNT` dice si el reporte trae puntaje (1) o no (0) — no lo juzga, sólo constata que
-- exista. La política es la que decide qué hacer con la ausencia, y aquí decide descalificar.
--
-- Va en un changeset propio y no editando el seed: la 005 y la 010 ya se aplicaron, y reescribirlas
-- rompe a Liquibase por checksum en toda base que ya las tenga.

-- changeset scoring-service:011-allow-fico-scores-count
-- Ampliar el catálogo de variables que el motor sabe evaluar.
ALTER TABLE scoring.scoring_rules DROP CONSTRAINT chk_scoring_rules_type;

ALTER TABLE scoring.scoring_rules
    ADD CONSTRAINT chk_scoring_rules_type
        CHECK (rule_type IN (
            -- Comportamiento de pago
            'MORA_CHECK','WORST_ARREARS_BALANCE','BALANCE_CHECK','OVERDUE_ACCOUNTS_COUNT',
            'CURRENT_ACCOUNTS_COUNT','OVERDUE_PAYMENTS_COUNT','ARREARS_RECENCY_MONTHS',
            'PREVENTION_KEY_COUNT',
            -- Exposición y capacidad de pago
            'TOTAL_DEBT','CREDIT_UTILIZATION','MONTHLY_PAYMENT_LOAD','DEBT_TO_INCOME',
            -- Perfil crediticio
            'FICO_THRESHOLD','FICO_SCORES_COUNT','CREDIT_COUNT','CREDIT_HISTORY_MONTHS','INQUIRY_COUNT',
            -- Perfil de la persona
            'AGE_YEARS','MONTHLY_INCOME','EMPLOYMENT_MONTHS','DEPENDENTS_COUNT'));

-- changeset scoring-service:011-require-bureau-score
-- La regla, en todas las políticas vigentes. Se descalifica igual que con la clave de prevención:
-- el puntaje negativo deja rastro del porqué en el desglose, y la marca de descalificante es la
-- que corta.
INSERT INTO scoring.scoring_rules
    (rule_id, policy_id, rule_type, credit_type, operator, threshold_value,
     score_contribution, is_disqualifying, period_months, description)
SELECT gen_random_uuid(), p.policy_id, 'FICO_SCORES_COUNT', NULL, 'LTE', 0,
       -500, TRUE, NULL, 'El buró no trae puntaje para esta persona → rechazo inmediato'
  FROM scoring.scoring_policies p
 WHERE NOT EXISTS (
       SELECT 1 FROM scoring.scoring_rules r
        WHERE r.policy_id = p.policy_id
          AND r.rule_type = 'FICO_SCORES_COUNT');
