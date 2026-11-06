--liquibase formatted sql
--changeset notifications:016-aviso-de-desembolso-fallido author:system
--comment D63: the borrower hears that the money could not be deposited and that nothing is owed.

-- A credit-journey event: its key is its type, as 012 left every one of them.
INSERT INTO notifications.notification_policies
    (policy_id, event_type, event_key, value_tier, channel_strategy, primary_channel, version, status, created_at) VALUES
    ('b3333333-3333-3333-3333-33333333333f', 'DISBURSEMENT_FAILED', 'DISBURSEMENT_FAILED', 'ALTO', 'SIMULTANEOUS',
     'PUSH_NOTIFICATION', 1, 'ACTIVE', NOW());

INSERT INTO notifications.notification_templates (template_id, event_type, event_key, channel, locale, subject, body) VALUES
    (gen_random_uuid(), 'DISBURSEMENT_FAILED', 'DISBURSEMENT_FAILED', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     'No pudimos depositar tu crédito'),
    (gen_random_uuid(), 'DISBURSEMENT_FAILED', 'DISBURSEMENT_FAILED', 'WHATSAPP', 'es-MX', NULL,
     '{{firstName}}, no pudimos depositar {{amount}} en tu cuenta. {{outcome}} No tienes nada que pagar.'),
    (gen_random_uuid(), 'DISBURSEMENT_FAILED', 'DISBURSEMENT_FAILED', 'EMAIL', 'es-MX', 'No pudimos depositar tu crédito',
     'Hola {{firstName}}, no pudimos depositar {{amount}} en tu cuenta. {{outcome}} No tienes nada que pagar.');
