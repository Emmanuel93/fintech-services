--liquibase formatted sql
--changeset closing:010-seed-calendar-and-policies
--comment Calendario MX y una política por comportamiento de producto.

INSERT INTO closing.business_calendars (calendar_code, zone_id, description) VALUES
    ('MX', 'America/Mexico_City', 'Calendario de negocio México — hábiles bancarios')
ON CONFLICT DO NOTHING;

-- Política GLOBAL: lo que aplica a un producto que no declara la suya.
INSERT INTO closing.close_cycle_policies
    (scope_type, scope_value, version, status, effective_date, calendar_code,
     phases, accrual_basis, cutoff_rule, payment_due_offset_days, non_business_day_shift,
     reconcile_tolerance, on_unreconciled)
VALUES
    ('GLOBAL', NULL, 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,POSTING_DRAIN,SEAL', 'ACTUAL_360', 'NONE', 0, 'NEXT',
     0.01, 'BLOCK_SEAL')
ON CONFLICT DO NOTHING;

-- No revolventes: el corte lo marca la cadencia del plan, pero lo DERIVA y lo persiste el cierre
-- (ver 005). `payment_due_offset_days = 3` es el período de gracia con el que hoy trabaja charges.
INSERT INTO closing.close_cycle_policies
    (scope_type, scope_value, version, status, effective_date, calendar_code,
     phases, accrual_basis, cutoff_rule, payment_due_offset_days, non_business_day_shift,
     reconcile_tolerance, on_unreconciled)
VALUES
    ('PRODUCT_TYPE', 'PERSONAL_LOAN', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE',
     'ACTUAL_360', 'INSTALLMENT_DUE_DATE', 3, 'NEXT', 0.01, 'BLOCK_SEAL'),
    ('PRODUCT_TYPE', 'PAYROLL_LOAN', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE',
     'ACTUAL_360', 'INSTALLMENT_DUE_DATE', 3, 'NEXT', 0.01, 'BLOCK_SEAL'),
    ('PRODUCT_TYPE', 'MICRO_LOAN', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE',
     'ACTUAL_360', 'INSTALLMENT_DUE_DATE', 3, 'NEXT', 0.01, 'BLOCK_SEAL'),
    ('PRODUCT_TYPE', 'SME_LOAN', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE',
     'ACTUAL_360', 'INSTALLMENT_DUE_DATE', 3, 'NEXT', 0.01, 'BLOCK_SEAL'),
    -- Revolventes: ciclo anclado al día de activación. Es el corte que hoy NO existe en la
    -- plataforma: `hasCutoffDate` está sembrado en true y ninguna línea lo lee.
    ('PRODUCT_TYPE', 'CREDIT_CARD', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE',
     'ACTUAL_360', 'CYCLE_FROM_ACTIVATION', 20, 'NEXT', 0.01, 'BLOCK_SEAL'),
    ('PRODUCT_TYPE', 'REVOLVING_LINE', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE',
     'ACTUAL_360', 'CYCLE_FROM_ACTIVATION', 20, 'NEXT', 0.01, 'BLOCK_SEAL'),
    -- Línea de distribuidor: se liquida comisión a un tercero, así que no se sella con dudas.
    ('PRODUCT_TYPE', 'DISTRIBUTOR_LINE', 1, 'ACTIVE', DATE '2020-01-01', 'MX',
     'RECONCILE,ACCRUAL,DELINQUENCY,CUTOFF,POSTING_DRAIN,SEAL,PROPAGATE,COMMISSION',
     'ACTUAL_360', 'CYCLE_FROM_ACTIVATION', 15, 'NEXT', 0.00, 'BLOCK_SEAL')
ON CONFLICT DO NOTHING;
