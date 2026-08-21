--liquibase formatted sql
--changeset channels:006-seed-default-channels

INSERT INTO channels.channels (channel_id, channel_type, status, allowed_intents,
                               session_ttl_minutes, max_idle_minutes, rate_limit_per_hour,
                               created_at, updated_at)
VALUES
    (gen_random_uuid(), 'MOBILE_APP',      'ACTIVE',
     'CREDIT_APPLICATION,PAYMENT,ACCOUNT_INQUIRY,REFINANCING,SUPPORT', 30, 10, 200,
     NOW(), NOW()),
    (gen_random_uuid(), 'WEB',             'ACTIVE',
     'CREDIT_APPLICATION,PAYMENT,ACCOUNT_INQUIRY,REFINANCING,SUPPORT', 30, 10, 200,
     NOW(), NOW()),
    (gen_random_uuid(), 'API_B2B',         'ACTIVE',
     'CREDIT_APPLICATION,REFINANCING', 60, 30, 500,
     NOW(), NOW()),
    (gen_random_uuid(), 'BRANCH',          'ACTIVE',
     'CREDIT_APPLICATION,PAYMENT,ACCOUNT_INQUIRY,REFINANCING,SUPPORT', 120, 30, 200,
     NOW(), NOW()),
    (gen_random_uuid(), 'FIELD_PROMOTER',  'ACTIVE',
     'CREDIT_APPLICATION', 60, 20, 50,
     NOW(), NOW()),
    (gen_random_uuid(), 'CORRESPONDENT',   'ACTIVE',
     'CREDIT_APPLICATION,PAYMENT', 60, 20, 100,
     NOW(), NOW()),
    (gen_random_uuid(), 'IVR',             'ACTIVE',
     'PAYMENT,ACCOUNT_INQUIRY,SUPPORT', 15, 5, 300,
     NOW(), NOW()),
    (gen_random_uuid(), 'WHATSAPP',        'ACTIVE',
     'CREDIT_APPLICATION,ACCOUNT_INQUIRY,SUPPORT', 60, 15, 100,
     NOW(), NOW())
ON CONFLICT (channel_type) DO NOTHING;
