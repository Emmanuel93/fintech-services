--liquibase formatted sql
--changeset notifications:013-seed-backoffice-policies author:system
--comment Avisos dirigidos al personal. Sin política no se manda nada — por diseño.

-- ─────────────────────────────────────────────────────────────────────────────
-- La campana de la consola.
--
-- Regla que decide qué entra aquí: **la campana es para lo que es tuyo; la bandeja es para lo del
-- equipo**. Si un hecho no tiene una persona identificada, no lleva aviso — lleva bandeja. Por eso
-- no hay política para «entró un alta nueva»: al crearse un prospecto todavía no hay ejecutivo
-- asignado, y avisarle a todo el que tenga el rol convertiría la campana en un segundo tablero.
--
-- Canal único IN_APP: estos avisos viven dentro de la consola. Un empleado no necesita un WhatsApp
-- porque le reasignaron cartera, y mandárselo enseñaría al personal a ignorar el canal por el que
-- sí hay que avisarle de cosas urgentes.
--
-- `event_type` va NULL a propósito: son claves que no están en el catálogo del journey de crédito,
-- y ese es justo el punto de que la llave sea `event_key`.
-- ─────────────────────────────────────────────────────────────────────────────

INSERT INTO notifications.notification_policies
    (policy_id, event_type, event_key, value_tier, channel_strategy, primary_channel,
     version, status, created_at) VALUES
    ('b0000001-0000-0000-0000-000000000001', NULL, 'STAFF_CLIENTES_ASIGNADOS',   'MEDIO',
     'SEQUENTIAL_FALLBACK', 'IN_APP', 1, 'ACTIVE', NOW()),
    ('b0000002-0000-0000-0000-000000000002', NULL, 'STAFF_DOCUMENTOS_RECIBIDOS', 'ALTO',
     'SEQUENTIAL_FALLBACK', 'IN_APP', 1, 'ACTIVE', NOW()),
    ('b0000003-0000-0000-0000-000000000003', NULL, 'STAFF_SOLICITUD_RESUELTA',   'MEDIO',
     'SEQUENTIAL_FALLBACK', 'IN_APP', 1, 'ACTIVE', NOW());

-- Plantillas. IN_APP no sale a ningún proveedor —el mensaje ES el registro y la campana lo lee de
-- ahí—, pero necesita cuerpo: es el texto que el empleado ve.
INSERT INTO notifications.notification_templates
    (template_id, event_type, event_key, channel, locale, subject, body) VALUES
    (gen_random_uuid(), NULL, 'STAFF_CLIENTES_ASIGNADOS', 'IN_APP', 'es-MX',
     'Se te asignó cartera',
     'Ahora llevas {{cantidad}} cliente(s) más. Revisa tu cartera para ponerte al día con ellos.'),
    (gen_random_uuid(), NULL, 'STAFF_DOCUMENTOS_RECIBIDOS', 'IN_APP', 'es-MX',
     'Llegaron los documentos que pediste',
     '{{cliente}} subió los documentos de la solicitud {{solicitud}}. Ya puedes continuar el análisis.'),
    (gen_random_uuid(), NULL, 'STAFF_SOLICITUD_RESUELTA', 'IN_APP', 'es-MX',
     'Se resolvió una solicitud de tu cliente',
     'La solicitud de {{cliente}} quedó {{resultado}}.');
