-- ── CC-IND-STD-V1: Tarjeta de crédito ────────────────────────────────────────
-- amount_step=1000 → líneas: $3k, $4k, $5k, …, $80k
INSERT INTO credit_product.credit_product_definitions (
    product_definition_id, product_code, product_version, product_type,
    name, description, status, target_audience, currency,
    nominal_rate_annual, moratorium_rate_annual,
    min_term, max_term, default_term,
    min_amount, max_amount,
    default_credit_line, min_credit_line, max_credit_line,
    amount_step,
    amortization_type, default_payment_frequency,
    min_approval_score, default_approval_flow,
    opening_fee_rate, prepayment_fee_rate,
    capabilities, created_at, activated_at, version
) VALUES (
    'a1000001-0000-0000-0000-000000000006',
    'CC-IND-STD-V1', 1, 'CREDIT_CARD',
    'Tarjeta de Crédito Estándar',
    'Tarjeta revolvente para persona física. Línea asignada al activar. Estado de cuenta mensual.',
    'ACTIVE', 'B2C', 'MXN',
    0.3600, 0.6000,
    NULL, NULL, NULL,
    NULL, NULL,
    10000.00, 3000.00, 80000.00,
    1000,
    NULL, 'MONTHLY',
    280, 'AUTOMATIC',
    0.0000, 0.0000,
    '{"hasAmortizationSchedule":false,"hasCreditLimit":true,"allowsMultipleDispositions":true,"dispositionType":"SELF_USE","hasCutoffDate":true,"hasMinimumPayment":true,"allowsMultipleObligors":false,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false}',
    NOW(), NOW(), 0
);

-- ── ML-IND-STD-V1: Micro préstamo ────────────────────────────────────────────
-- amount_step=500 → montos: $500, $1k, $1.5k, $2k, …, $10k
INSERT INTO credit_product.credit_product_definitions (
    product_definition_id, product_code, product_version, product_type,
    name, description, status, target_audience, currency,
    nominal_rate_annual, moratorium_rate_annual,
    min_term, max_term, default_term,
    min_amount, max_amount,
    default_credit_line, min_credit_line, max_credit_line,
    amount_step,
    amortization_type, default_payment_frequency,
    min_approval_score, default_approval_flow,
    opening_fee_rate, prepayment_fee_rate,
    capabilities, created_at, activated_at, version
) VALUES (
    'a1000001-0000-0000-0000-000000000007',
    'ML-IND-STD-V1', 1, 'MICRO_LOAN',
    'Micro Préstamo',
    'Préstamo de bajo monto para personas físicas con historial limitado. Inclusión financiera.',
    'ACTIVE', 'B2C', 'MXN',
    0.5200, 0.8000,
    1, 6, 3,
    500.00, 10000.00,
    NULL, NULL, NULL,
    500,
    'FRENCH', 'WEEKLY',
    80, 'AUTOMATIC',
    0.0200, 0.0000,
    '{"hasAmortizationSchedule":true,"hasCreditLimit":false,"allowsMultipleDispositions":false,"dispositionType":"SELF_USE","hasCutoffDate":false,"hasMinimumPayment":false,"allowsMultipleObligors":false,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false}',
    NOW(), NOW(), 0
);

-- ── Tipos de party elegibles ──────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_eligible_party_types (product_definition_id, party_type) VALUES
('a1000001-0000-0000-0000-000000000006', 'INDIVIDUAL'),
('a1000001-0000-0000-0000-000000000007', 'INDIVIDUAL');

-- ── Documentos requeridos ─────────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_required_documents (product_definition_id, document_type, mandatory) VALUES
('a1000001-0000-0000-0000-000000000006', 'INCOME_PROOF',  TRUE),
('a1000001-0000-0000-0000-000000000006', 'ADDRESS_PROOF', TRUE),
('a1000001-0000-0000-0000-000000000007', 'INE',           TRUE),
('a1000001-0000-0000-0000-000000000007', 'ADDRESS_PROOF', FALSE);

-- ── Canales ───────────────────────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_channel_availability (product_definition_id, channel_type) VALUES
('a1000001-0000-0000-0000-000000000006', 'WEB'),
('a1000001-0000-0000-0000-000000000006', 'MOBILE_APP'),
('a1000001-0000-0000-0000-000000000006', 'BRANCH'),
('a1000001-0000-0000-0000-000000000007', 'MOBILE_APP'),
('a1000001-0000-0000-0000-000000000007', 'BRANCH');

-- ── Frecuencias de pago ───────────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_payment_frequencies (product_definition_id, payment_frequency) VALUES
('a1000001-0000-0000-0000-000000000006', 'MONTHLY'),
('a1000001-0000-0000-0000-000000000007', 'WEEKLY'),
('a1000001-0000-0000-0000-000000000007', 'BIWEEKLY');
