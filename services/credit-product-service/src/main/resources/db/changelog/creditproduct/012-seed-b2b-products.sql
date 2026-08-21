-- ─────────────────────────────────────────────────────────────────────────────
-- Seed 012: B2B products — SME_LOAN + BUSINESS_REVOLVING_LINE
--
-- These demonstrate B2B configurability with:
--   - eligiblePartyTypes = BUSINESS
--   - tiered rate cards by amount band / risk tier
--   - eligibility rules (MIN_MONTHLY_INCOME, REQUIRED_PARTY_TYPE, MAX_DEBT_TO_INCOME_RATIO)
-- ─────────────────────────────────────────────────────────────────────────────

-- ─────────────────────────────────────────────────────────────────
-- SME-LOAN-STD-V1: Crédito PYME a plazo — INSTALLMENT, B2B
-- amount_step=5000 → montos: $50k, $55k, $60k, …, $5M
-- ─────────────────────────────────────────────────────────────────
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
    capabilities,
    created_at, activated_at, version
) VALUES (
    'b2000001-0000-0000-0000-000000000001',
    'SME-LOAN-STD-V1', 1, 'SME_LOAN',
    'Crédito PYME Estándar',
    'Préstamo a plazo para pequeñas y medianas empresas. Amortización francesa. Comité de crédito para montos > $1M. Tasas por tramo de monto.',
    'ACTIVE', 'B2B', 'MXN',
    0.2400, 0.4000,
    6, 48, 24,
    50000.00, 5000000.00,
    NULL, NULL, NULL,
    5000,
    'FRENCH', 'MONTHLY',
    300, 'COMMITTEE',
    0.0150, 0.0300,
    '{
        "hasAmortizationSchedule": true,
        "hasCreditLimit": false,
        "allowsMultipleDispositions": false,
        "dispositionType": "SELF_USE",
        "hasCutoffDate": false,
        "hasMinimumPayment": false,
        "allowsMultipleObligors": false,
        "commissionsEnabled": false,
        "requiresBeneficiaryPartyId": false
    }'::jsonb,
    NOW(), NOW(), 0
);

-- Rate cards: B2B SME_LOAN — tiered by amount band (smaller deals pay more)
INSERT INTO credit_product.rate_cards (
    rate_card_id, product_definition_id,
    tier_band, min_amount, max_amount, min_term, max_term,
    nominal_rate, moratorium_rate
) VALUES
-- Tramo bajo: $50k–$500k — 26% (mayor riesgo por tamaño)
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000001',
 NULL, 50000.00, 500000.00, NULL, NULL, 0.2600, 0.4200),
-- Tramo medio: $500k–$2M — 22%
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000001',
 NULL, 500000.01, 2000000.00, NULL, NULL, 0.2200, 0.3800),
-- Tramo alto: $2M–$5M — 18% (economía de escala)
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000001',
 NULL, 2000000.01, 5000000.00, NULL, NULL, 0.1800, 0.3400);

-- Eligibility rules
INSERT INTO credit_product.eligibility_rules (
    rule_id, product_definition_id,
    rule_type, operator, threshold_value, string_value, error_code
) VALUES
-- Solo empresas (partyType=BUSINESS)
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000001',
 'REQUIRED_PARTY_TYPE', NULL, NULL, 'BUSINESS', 'ELIG_SME_PARTY_TYPE_REQUIRED'),
-- DTI máx 50% (las empresas pueden endeudarse más que personas físicas)
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000001',
 'MAX_DEBT_TO_INCOME_RATIO', 'LTE', 0.50, NULL, 'ELIG_SME_MAX_DTI_EXCEEDED');

-- Eligible party types
INSERT INTO credit_product.credit_product_eligible_party_types (product_definition_id, party_type)
VALUES ('b2000001-0000-0000-0000-000000000001', 'BUSINESS');

-- Required documents
INSERT INTO credit_product.credit_product_required_documents (product_definition_id, document_type, mandatory)
VALUES
('b2000001-0000-0000-0000-000000000001', 'BANK_STATEMENT_3M', TRUE),
('b2000001-0000-0000-0000-000000000001', 'TAX_RETURN',        TRUE),
('b2000001-0000-0000-0000-000000000001', 'BUSINESS_LICENSE',  TRUE),
('b2000001-0000-0000-0000-000000000001', 'FINANCIAL_STATEMENTS', TRUE);

-- Channels
INSERT INTO credit_product.credit_product_channel_availability (product_definition_id, channel_type)
VALUES
('b2000001-0000-0000-0000-000000000001', 'BRANCH'),
('b2000001-0000-0000-0000-000000000001', 'API_PARTNER');

-- Payment frequencies
INSERT INTO credit_product.credit_product_payment_frequencies (product_definition_id, payment_frequency)
VALUES
('b2000001-0000-0000-0000-000000000001', 'MONTHLY'),
('b2000001-0000-0000-0000-000000000001', 'BIWEEKLY');


-- ─────────────────────────────────────────────────────────────────
-- BRL-BUS-STD-V1: Línea revolvente empresarial — REVOLVING, B2B
-- amount_step=10000 → líneas: $100k, $110k, $120k, …, $10M
-- ─────────────────────────────────────────────────────────────────
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
    capabilities,
    created_at, activated_at, version
) VALUES (
    'b2000001-0000-0000-0000-000000000002',
    'BRL-BUS-STD-V1', 1, 'BUSINESS_REVOLVING_LINE',
    'Línea Revolvente Empresarial',
    'Línea de crédito revolvente para empresas. Múltiples disposiciones. Estado de cuenta mensual. Ideal para capital de trabajo.',
    'ACTIVE', 'B2B', 'MXN',
    0.1800, 0.3200,
    NULL, NULL, NULL,
    NULL, NULL,
    500000.00, 100000.00, 10000000.00,
    10000,
    NULL, 'MONTHLY',
    350, 'MANUAL',
    0.0000, 0.0000,
    '{
        "hasAmortizationSchedule": false,
        "hasCreditLimit": true,
        "allowsMultipleDispositions": true,
        "dispositionType": "SELF_USE",
        "hasCutoffDate": true,
        "hasMinimumPayment": true,
        "allowsMultipleObligors": false,
        "commissionsEnabled": false,
        "requiresBeneficiaryPartyId": false
    }'::jsonb,
    NOW(), NOW(), 0
);

-- Rate cards: business revolving — tiered by risk tier
INSERT INTO credit_product.rate_cards (
    rate_card_id, product_definition_id,
    tier_band, min_amount, max_amount, min_term, max_term,
    nominal_rate, moratorium_rate
) VALUES
-- Tier 1 (mejor calificación): 16%
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000002',
 'T1', NULL, NULL, NULL, NULL, 0.1600, 0.3000),
-- Tier 2: 18% (flat — precio por defecto del producto)
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000002',
 'T2', NULL, NULL, NULL, NULL, 0.1800, 0.3200),
-- Tier 3: 22%
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000002',
 'T3', NULL, NULL, NULL, NULL, 0.2200, 0.3800);

-- Eligibility rules
INSERT INTO credit_product.eligibility_rules (
    rule_id, product_definition_id,
    rule_type, operator, threshold_value, string_value, error_code
) VALUES
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000002',
 'REQUIRED_PARTY_TYPE', NULL, NULL, 'BUSINESS', 'ELIG_BRL_PARTY_TYPE_REQUIRED'),
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000002',
 'MAX_DEBT_TO_INCOME_RATIO', 'LTE', 0.60, NULL, 'ELIG_BRL_MAX_DTI_EXCEEDED'),
(gen_random_uuid(), 'b2000001-0000-0000-0000-000000000002',
 'MIN_SCORE', 'GTE', 350, NULL, 'ELIG_BRL_MIN_SCORE_REQUIRED');

-- Eligible party types
INSERT INTO credit_product.credit_product_eligible_party_types (product_definition_id, party_type)
VALUES ('b2000001-0000-0000-0000-000000000002', 'BUSINESS');

-- Required documents
INSERT INTO credit_product.credit_product_required_documents (product_definition_id, document_type, mandatory)
VALUES
('b2000001-0000-0000-0000-000000000002', 'BANK_STATEMENT_3M',    TRUE),
('b2000001-0000-0000-0000-000000000002', 'TAX_RETURN',           TRUE),
('b2000001-0000-0000-0000-000000000002', 'BUSINESS_LICENSE',     TRUE),
('b2000001-0000-0000-0000-000000000002', 'FINANCIAL_STATEMENTS', TRUE);

-- Channels
INSERT INTO credit_product.credit_product_channel_availability (product_definition_id, channel_type)
VALUES
('b2000001-0000-0000-0000-000000000002', 'BRANCH'),
('b2000001-0000-0000-0000-000000000002', 'API_PARTNER'),
('b2000001-0000-0000-0000-000000000002', 'WEB');

-- Payment frequencies
INSERT INTO credit_product.credit_product_payment_frequencies (product_definition_id, payment_frequency)
VALUES ('b2000001-0000-0000-0000-000000000002', 'MONTHLY');


-- ─────────────────────────────────────────────────────────────────
-- Seed rate cards for existing B2C products (tiered by risk tier)
-- Adds T1/T2/T3 tiers for PERSONAL_LOAN — demonstrates configurability
-- ─────────────────────────────────────────────────────────────────
INSERT INTO credit_product.rate_cards (
    rate_card_id, product_definition_id,
    tier_band, min_amount, max_amount, min_term, max_term,
    nominal_rate, moratorium_rate
) VALUES
-- PERSONAL_LOAN by tier
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000001', 'T1', NULL, NULL, NULL, NULL, 0.2800, 0.5000),
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000001', 'T2', NULL, NULL, NULL, NULL, 0.3200, 0.5500),
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000001', 'T3', NULL, NULL, NULL, NULL, 0.3800, 0.6000),
-- REVOLVING_LINE by tier
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000002', 'T1', NULL, NULL, NULL, NULL, 0.3200, 0.5500),
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000002', 'T2', NULL, NULL, NULL, NULL, 0.3800, 0.6000),
-- DISTRIBUTOR_LINE by line amount
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000004', NULL, 100000.00, 1000000.00, NULL, NULL, 0.2200, 0.3800),
(gen_random_uuid(), 'a1000001-0000-0000-0000-000000000004', NULL, 1000000.01, 5000000.00, NULL, NULL, 0.1800, 0.3400);
