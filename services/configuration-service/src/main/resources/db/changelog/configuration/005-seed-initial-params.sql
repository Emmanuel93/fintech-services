-- Seed essential parameters referenced by domain spec in ACTIVE status.
-- Uses a fixed system UUID as created_by / approved_by (00000000-0000-0000-0000-000000000001).

INSERT INTO configuration.config_parameters
    (id, param_key, value, product_type, channel_type, version, status, effective_date,
     created_by, approved_by, previous_version_ref, created_at, updated_at)
VALUES
    -- D5: IVA rate (CF-04: critical param)
    (gen_random_uuid(), 'vat_rate', '0.16', NULL, NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW()),

    -- D5: grace period days (default 3)
    (gen_random_uuid(), 'grace_period_days', '3', 'PERSONAL_LOAN', NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW()),

    -- D6: return window hours (default 72)
    (gen_random_uuid(), 'return_window_hours', '72', NULL, NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW()),

    -- D8: max contact attempts per day (CONDUSEF)
    (gen_random_uuid(), 'max_contact_attempts_per_day', '3', NULL, NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW()),

    -- D8: contact allowed hours (CONDUSEF 08:00-20:00)
    (gen_random_uuid(), 'contact_allowed_hours', '08:00-20:00', NULL, NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW()),

    -- D2: score validity days for INDIVIDUAL
    (gen_random_uuid(), 'score_validity_days', '30', NULL, NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW()),

    -- D6: CoDi token TTL minutes
    (gen_random_uuid(), 'codi_token_ttl_minutes', '5', NULL, NULL, 1, 'ACTIVE', CURRENT_DATE,
     '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', NULL, NOW(), NOW())

ON CONFLICT DO NOTHING;
