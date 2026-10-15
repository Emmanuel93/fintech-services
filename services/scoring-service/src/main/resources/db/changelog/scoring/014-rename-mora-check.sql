-- El catálogo de variables se llama en inglés como el resto del código. `MORA_CHECK` era el único
-- nombre en español y además decía menos de lo que mide: no es «hay mora», es el peor atraso
-- histórico medido en días — que es justo lo que lo distingue de WORST_ARREARS_BALANCE, su gemelo
-- en pesos.
--
-- Va en changeset propio y no editando la 005/007/008/011: esas ya se aplicaron, y reescribirlas
-- rompe a Liquibase por checksum en toda base que las tenga. El orden importa — primero se amplía
-- la restricción para que acepte el nombre nuevo, luego se mueven las filas, y al final se retira
-- el viejo. Al revés, el UPDATE chocaría contra el CHECK.

-- changeset scoring-service:014-rename-mora-check-widen
ALTER TABLE scoring.scoring_rules DROP CONSTRAINT chk_scoring_rules_type;

ALTER TABLE scoring.scoring_rules
    ADD CONSTRAINT chk_scoring_rules_type
        CHECK (rule_type IN (
            -- Comportamiento de pago
            'MORA_CHECK','WORST_ARREARS_DAYS','WORST_ARREARS_BALANCE','BALANCE_CHECK',
            'OVERDUE_ACCOUNTS_COUNT','CURRENT_ACCOUNTS_COUNT','OVERDUE_PAYMENTS_COUNT',
            'ARREARS_RECENCY_MONTHS','PREVENTION_KEY_COUNT',
            -- Exposición y capacidad de pago
            'TOTAL_DEBT','CREDIT_UTILIZATION','MONTHLY_PAYMENT_LOAD','DEBT_TO_INCOME',
            -- Perfil crediticio
            'FICO_THRESHOLD','FICO_SCORES_COUNT','CREDIT_COUNT','CREDIT_HISTORY_MONTHS','INQUIRY_COUNT',
            -- Perfil de la persona
            'AGE_YEARS','MONTHLY_INCOME','EMPLOYMENT_MONTHS','DEPENDENTS_COUNT'));

-- changeset scoring-service:014-rename-mora-check-move
UPDATE scoring.scoring_rules SET rule_type = 'WORST_ARREARS_DAYS' WHERE rule_type = 'MORA_CHECK';

-- changeset scoring-service:014-rename-mora-check-narrow
-- Ya no queda ninguna fila con el nombre viejo; la restricción deja de admitirlo para que no vuelva
-- a entrar por un seed olvidado.
ALTER TABLE scoring.scoring_rules DROP CONSTRAINT chk_scoring_rules_type;

ALTER TABLE scoring.scoring_rules
    ADD CONSTRAINT chk_scoring_rules_type
        CHECK (rule_type IN (
            'WORST_ARREARS_DAYS','WORST_ARREARS_BALANCE','BALANCE_CHECK',
            'OVERDUE_ACCOUNTS_COUNT','CURRENT_ACCOUNTS_COUNT','OVERDUE_PAYMENTS_COUNT',
            'ARREARS_RECENCY_MONTHS','PREVENTION_KEY_COUNT',
            'TOTAL_DEBT','CREDIT_UTILIZATION','MONTHLY_PAYMENT_LOAD','DEBT_TO_INCOME',
            'FICO_THRESHOLD','FICO_SCORES_COUNT','CREDIT_COUNT','CREDIT_HISTORY_MONTHS','INQUIRY_COUNT',
            'AGE_YEARS','MONTHLY_INCOME','EMPLOYMENT_MONTHS','DEPENDENTS_COUNT'));
