-- ─────────────────────────────────────────────────────────────────────────────
-- Seed: 5 productos B2C / B2B2C iniciales  (version 1, ACTIVE)
-- Tasas en decimal: 0.3200 = 32% anual
-- amount_step: incremento mínimo de monto/línea (múltiplos limpios)
-- ─────────────────────────────────────────────────────────────────────────────

-- ── PL-IND-STD-V1: Préstamo personal ────────────────────────────────────────
-- amount_step=1000 → montos: $5k, $6k, $7k, …, $150k
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
    'a1000001-0000-0000-0000-000000000001',
    'PL-IND-STD-V1', 1, 'PERSONAL_LOAN',
    'Préstamo Personal Estándar',
    'Crédito a plazo fijo para persona física. Amortización francesa (cuotas iguales). Desembolso vía SPEI.',
    'ACTIVE', 'B2C', 'MXN',
    0.3200, 0.5500,
    3, 36, 12,
    5000.00, 150000.00,
    NULL, NULL, NULL,
    1000,
    'FRENCH', 'MONTHLY',
    200, 'AUTOMATIC',
    0.0100, 0.0200,
    '{"hasAmortizationSchedule":true,"hasCreditLimit":false,"allowsMultipleDispositions":false,"dispositionType":"SELF_USE","hasCutoffDate":false,"hasMinimumPayment":false,"allowsMultipleObligors":false,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false}',
    NOW(), NOW(), 0
);

-- ── RL-IND-STD-V1: Línea revolvente personal ─────────────────────────────────
-- amount_step=1000 → líneas: $5k, $6k, $7k, …, $50k
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
    'a1000001-0000-0000-0000-000000000002',
    'RL-IND-STD-V1', 1, 'REVOLVING_LINE',
    'Línea Revolvente Personal',
    'Línea de crédito revolvente para persona física. Múltiples disposiciones. Estado de cuenta mensual.',
    'ACTIVE', 'B2C', 'MXN',
    0.3800, 0.6000,
    NULL, NULL, NULL,
    NULL, NULL,
    10000.00, 5000.00, 50000.00,
    1000,
    NULL, 'MONTHLY',
    250, 'AUTOMATIC',
    0.0100, 0.0000,
    '{"hasAmortizationSchedule":false,"hasCreditLimit":true,"allowsMultipleDispositions":true,"dispositionType":"SELF_USE","hasCutoffDate":true,"hasMinimumPayment":true,"allowsMultipleObligors":false,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false}',
    NOW(), NOW(), 0
);

-- ── PY-IND-STD-V1: Crédito de nómina ─────────────────────────────────────────
-- amount_step=500 → montos: $5k, $5.5k, $6k, …, $100k
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
    'a1000001-0000-0000-0000-000000000003',
    'PY-IND-STD-V1', 1, 'PAYROLL_LOAN',
    'Crédito de Nómina',
    'Crédito con domiciliación a nómina. Tasa preferencial por garantía de pago vía descuento de nómina.',
    'ACTIVE', 'B2C', 'MXN',
    0.2200, 0.4000,
    3, 24, 12,
    5000.00, 100000.00,
    NULL, NULL, NULL,
    500,
    'FRENCH', 'BIWEEKLY',
    150, 'AUTOMATIC',
    0.0050, 0.0000,
    '{"hasAmortizationSchedule":true,"hasCreditLimit":false,"allowsMultipleDispositions":false,"dispositionType":"PAYROLL","hasCutoffDate":false,"hasMinimumPayment":false,"allowsMultipleObligors":false,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false}',
    NOW(), NOW(), 0
);

-- ── DL-DIST-STD-V1: Línea distribuidora ──────────────────────────────────────
-- amount_step=5000 → líneas: $100k, $105k, $110k, …, $5M
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
    'a1000001-0000-0000-0000-000000000004',
    'DL-DIST-STD-V1', 1, 'DISTRIBUTOR_LINE',
    'Línea Distribuidora',
    'Línea para distribuidores. El distribuidor es el obligor; los créditos fluyen a terceros beneficiarios. Comité.',
    'ACTIVE', 'B2B2C', 'MXN',
    0.2000, 0.3600,
    NULL, NULL, NULL,
    NULL, NULL,
    500000.00, 100000.00, 5000000.00,
    5000,
    'BULLET', 'MONTHLY',
    300, 'COMMITTEE',
    0.0000, 0.0000,
    '{"hasAmortizationSchedule":false,"hasCreditLimit":true,"allowsMultipleDispositions":true,"dispositionType":"THIRD_PARTY_CREDIT","hasCutoffDate":true,"hasMinimumPayment":true,"allowsMultipleObligors":false,"commissionsEnabled":true,"requiresBeneficiaryPartyId":true}',
    NOW(), NOW(), 0
);

-- ── GL-IND-STD-V1: Crédito grupal ────────────────────────────────────────────
-- amount_step=500 → montos: $5k, $5.5k, $6k, …, $50k
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
    'a1000001-0000-0000-0000-000000000005',
    'GL-IND-STD-V1', 1, 'GROUP_LOAN',
    'Crédito Grupal',
    'Crédito solidario para grupos de 3–10 personas físicas con garantía mutua. Amortización alemana.',
    'ACTIVE', 'B2C', 'MXN',
    0.2800, 0.5000,
    6, 24, 12,
    5000.00, 50000.00,
    NULL, NULL, NULL,
    500,
    'GERMAN', 'WEEKLY',
    120, 'MANUAL',
    0.0050, 0.0000,
    '{"hasAmortizationSchedule":true,"hasCreditLimit":false,"allowsMultipleDispositions":false,"dispositionType":"SELF_USE","hasCutoffDate":false,"hasMinimumPayment":false,"allowsMultipleObligors":true,"commissionsEnabled":false,"requiresBeneficiaryPartyId":false}',
    NOW(), NOW(), 0
);

-- ── Tipos de party elegibles ──────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_eligible_party_types (product_definition_id, party_type) VALUES
('a1000001-0000-0000-0000-000000000001', 'INDIVIDUAL'),
('a1000001-0000-0000-0000-000000000002', 'INDIVIDUAL'),
('a1000001-0000-0000-0000-000000000003', 'INDIVIDUAL'),
('a1000001-0000-0000-0000-000000000004', 'DISTRIBUTOR'),
('a1000001-0000-0000-0000-000000000005', 'INDIVIDUAL');

-- ── Documentos requeridos ─────────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_required_documents (product_definition_id, document_type, mandatory) VALUES
('a1000001-0000-0000-0000-000000000001', 'INCOME_PROOF',          TRUE),
('a1000001-0000-0000-0000-000000000001', 'ADDRESS_PROOF',         TRUE),
('a1000001-0000-0000-0000-000000000002', 'INCOME_PROOF',          TRUE),
('a1000001-0000-0000-0000-000000000002', 'ADDRESS_PROOF',         TRUE),
('a1000001-0000-0000-0000-000000000003', 'PAYROLL_STUB',          TRUE),
('a1000001-0000-0000-0000-000000000003', 'EMPLOYMENT_LETTER',     TRUE),
('a1000001-0000-0000-0000-000000000003', 'ADDRESS_PROOF',         TRUE),
('a1000001-0000-0000-0000-000000000004', 'DISTRIBUTOR_AGREEMENT', TRUE),
('a1000001-0000-0000-0000-000000000004', 'BANK_STATEMENT_3M',     TRUE),
('a1000001-0000-0000-0000-000000000004', 'ADDRESS_PROOF',         TRUE),
('a1000001-0000-0000-0000-000000000005', 'GROUP_CHARTER',         TRUE),
('a1000001-0000-0000-0000-000000000005', 'ADDRESS_PROOF',         TRUE);

-- ── Canales ───────────────────────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_channel_availability (product_definition_id, channel_type) VALUES
('a1000001-0000-0000-0000-000000000001', 'WEB'),
('a1000001-0000-0000-0000-000000000001', 'MOBILE_APP'),
('a1000001-0000-0000-0000-000000000001', 'BRANCH'),
('a1000001-0000-0000-0000-000000000002', 'WEB'),
('a1000001-0000-0000-0000-000000000002', 'MOBILE_APP'),
('a1000001-0000-0000-0000-000000000002', 'BRANCH'),
('a1000001-0000-0000-0000-000000000003', 'WEB'),
('a1000001-0000-0000-0000-000000000003', 'MOBILE_APP'),
('a1000001-0000-0000-0000-000000000003', 'BRANCH'),
('a1000001-0000-0000-0000-000000000004', 'BRANCH'),
('a1000001-0000-0000-0000-000000000004', 'API_PARTNER'),
('a1000001-0000-0000-0000-000000000005', 'BRANCH'),
('a1000001-0000-0000-0000-000000000005', 'MOBILE_APP');

-- ── Frecuencias de pago ───────────────────────────────────────────────────────
INSERT INTO credit_product.credit_product_payment_frequencies (product_definition_id, payment_frequency) VALUES
('a1000001-0000-0000-0000-000000000001', 'MONTHLY'),
('a1000001-0000-0000-0000-000000000001', 'BIWEEKLY'),
('a1000001-0000-0000-0000-000000000002', 'MONTHLY'),
('a1000001-0000-0000-0000-000000000003', 'BIWEEKLY'),
('a1000001-0000-0000-0000-000000000004', 'MONTHLY'),
('a1000001-0000-0000-0000-000000000005', 'WEEKLY'),
('a1000001-0000-0000-0000-000000000005', 'BIWEEKLY');
