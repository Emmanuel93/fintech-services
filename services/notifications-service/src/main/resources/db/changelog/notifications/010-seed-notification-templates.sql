--liquibase formatted sql
--changeset notifications:010-seed-notification-templates author:system
--comment Copy real de T2_notifications.md §Enumeración completa — es-MX, alcance v1.

INSERT INTO notifications.notification_templates (template_id, event_type, channel, locale, subject, body) VALUES
    (gen_random_uuid(), 'OFFER_PRESENTED', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     'Tu oferta ya está lista'),
    (gen_random_uuid(), 'OFFER_PRESENTED', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, tenemos una oferta de crédito para ti: {{offeredAmount}} a {{offeredTerm}} meses, tasa {{nominalRate}}% (CAT {{cat}}%). Válida hasta {{validUntil}}. Revísala aquí: {{link}}'),
    (gen_random_uuid(), 'OFFER_PRESENTED', 'EMAIL', 'es-MX', 'Tu oferta de crédito, válida hasta {{validUntil}}',
     'Hola {{nombre}}, tu oferta de crédito ya está lista: {{offeredAmount}} a {{offeredTerm}} meses, tasa {{nominalRate}}% (CAT {{cat}}%). Válida hasta {{validUntil}}. Complétala aquí: {{link}}'),

    (gen_random_uuid(), 'WELCOME_ACTIVATED', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     '¡Tu cuenta ya está activa!'),
    (gen_random_uuid(), 'WELCOME_ACTIVATED', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, tu {{productType}} ya está activo. 3 pasos para empezar: 1) revisa tu estado de cuenta, 2) configura tu recordatorio de pago, 3) dispón de tu línea si aplica. Todo desde la app: {{link}}'),
    (gen_random_uuid(), 'WELCOME_ACTIVATED', 'EMAIL', 'es-MX', 'Bienvenido a tu {{productType}} — guía rápida',
     'Hola {{nombre}}, bienvenido. Tu {{productType}} ya está activo con un límite de {{creditLimit}} a una tasa de {{nominalRate}}%. Aquí tienes una guía rápida para empezar: {{link}}'),

    (gen_random_uuid(), 'DISBURSEMENT_COMPLETED', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     'Recibiste {{amount}} en tu wallet'),
    (gen_random_uuid(), 'DISBURSEMENT_COMPLETED', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, tu disposición de {{amount}} ya está disponible en tu wallet.'),

    (gen_random_uuid(), 'PAYMENT_REMINDER', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     'Tu pago de {{installmentAmount}} vence el {{dueDate}}'),
    (gen_random_uuid(), 'PAYMENT_REMINDER', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, tu próximo pago de {{installmentAmount}} vence el {{dueDate}}. Paga fácil desde la app: {{link}}'),

    (gen_random_uuid(), 'INSTALLMENT_PAID', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     '¡Cuota #{{installmentNumber}} pagada!'),
    (gen_random_uuid(), 'INSTALLMENT_PAID', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, tu cuota #{{installmentNumber}} de {{totalAmount}} quedó cubierta por completo. Te faltan {{cuotasRestantes}} para liquidar tu crédito. ¡Vas muy bien!'),

    (gen_random_uuid(), 'LOAN_SETTLED', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     '¡Liquidaste tu crédito por completo!'),
    (gen_random_uuid(), 'LOAN_SETTLED', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, ¡felicidades! Acabas de liquidar tu {{productType}} por completo. Gracias por tu confianza a lo largo de este crédito.'),
    (gen_random_uuid(), 'LOAN_SETTLED', 'EMAIL', 'es-MX', 'Enhorabuena — tu crédito {{productType}} está liquidado',
     'Hola {{nombre}}, ¡felicidades! Liquidaste por completo tu {{productType}}. Gracias por tu confianza a lo largo de este crédito — esperamos seguir acompañándote.'),

    -- Mora: comparte disparador con PAYMENT_REMINDER pero no mensaje. Decir "tu
    -- pago vence el 9 de septiembre" cuando ya es 15 confunde a quien tiene que
    -- ponerse al corriente; aquí se nombra la mensualidad y los días de atraso.
    (gen_random_uuid(), 'PAYMENT_OVERDUE', 'PUSH_NOTIFICATION', 'es-MX', NULL,
     'Tu pago de {{installmentAmount}} venció hace {{daysPastDue}} días'),
    (gen_random_uuid(), 'PAYMENT_OVERDUE', 'WHATSAPP', 'es-MX', NULL,
     '{{nombre}}, la mensualidad {{installmentNumber}} de {{totalInstallments}} venció el {{dueDate}} y lleva {{daysPastDue}} días de atraso. Ponte al corriente para dejar de generar moratorios: {{link}}'),
    (gen_random_uuid(), 'PAYMENT_OVERDUE', 'EMAIL', 'es-MX', 'Tu pago está vencido',
     '{{nombre}}, la mensualidad {{installmentNumber}} de {{totalInstallments}} venció el {{dueDate}}. Son {{installmentAmount}} y lleva {{daysPastDue}} días de atraso.');
