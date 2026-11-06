--liquibase formatted sql
--changeset closing:020-corte-quincenal-y-dias-2025
--comment The fortnight as a cutoff rule on two fixed days (the distributor line: the 6th and the 21st, paid by the 18th and the 3rd); and the bank holidays of 2025.

-- The policy table accepted four rules and the products declare six: a fixed weekday (018 tried to lift the limit with
-- the wrong constraint name) and now the fortnight. Accounts carry the product's cycle themselves; the policy is what an
-- account opened before its product declared one falls back to.
ALTER TABLE closing.close_cycle_policies DROP CONSTRAINT IF EXISTS chk_policy_cutoff;
ALTER TABLE closing.close_cycle_policies ADD CONSTRAINT chk_policy_cutoff CHECK (cutoff_rule IN
    ('NONE', 'INSTALLMENT_DUE_DATE', 'CYCLE_FROM_ACTIVATION', 'DAY_OF_MONTH', 'DAY_OF_WEEK', 'SEMI_MONTHLY'));

-- The fortnight cuts on two fixed days of the month and may be paid by two fixed days: "6,21" and "18,3". Without days
-- it is the calendar fortnight (the 15th and the last day) paid the offset after the cutoff.
ALTER TABLE closing.close_cycle_policies ADD COLUMN cutoff_days VARCHAR(16);
ALTER TABLE closing.close_cycle_policies ADD COLUMN payment_due_days VARCHAR(16);
ALTER TABLE closing.account_close_profiles ADD COLUMN billing_cutoff_days VARCHAR(16);
ALTER TABLE closing.account_close_profiles ADD COLUMN billing_due_days VARCHAR(16);

-- The distributor line cuts on the 6th and on the 21st of every month, as the reference system does, and each statement
-- is paid by the 18th and by the 3rd of the next month, with no minimum: it asks for its fortnight's payments and
-- charges.
UPDATE closing.close_cycle_policies
   SET cutoff_rule = 'SEMI_MONTHLY', cutoff_day = NULL, payment_due_offset_days = 0, minimum_payment_rate = NULL,
       cutoff_days = '6,21', payment_due_days = '18,3'
 WHERE scope_type = 'PRODUCT_TYPE' AND scope_value = 'DISTRIBUTOR_LINE' AND status = 'ACTIVE';

-- The bank holidays of 2025, as the CNBV published them in the DOF: a history that starts on 1 January 2025 (the
-- distributor suite of the end-to-end run) dates its payments and its SPEI with them. 2 November fell on a Sunday.
INSERT INTO closing.calendar_days (calendar_code, day, is_business, label) VALUES
    ('MX', '2025-01-01', FALSE, 'Año Nuevo'),
    ('MX', '2025-02-03', FALSE, 'Día de la Constitución'),
    ('MX', '2025-03-17', FALSE, 'Natalicio de Benito Juárez'),
    ('MX', '2025-04-17', FALSE, 'Jueves Santo'),
    ('MX', '2025-04-18', FALSE, 'Viernes Santo'),
    ('MX', '2025-05-01', FALSE, 'Día del Trabajo'),
    ('MX', '2025-09-16', FALSE, 'Día de la Independencia'),
    ('MX', '2025-11-17', FALSE, 'Revolución Mexicana'),
    ('MX', '2025-12-12', FALSE, 'Día del empleado bancario'),
    ('MX', '2025-12-25', FALSE, 'Navidad')
ON CONFLICT DO NOTHING;
