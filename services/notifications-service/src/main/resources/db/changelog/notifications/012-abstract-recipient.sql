--liquibase formatted sql
--changeset notifications:012-abstract-recipient author:system
--comment El destinatario deja de ser un cliente y pasa a ser una entidad abstracta.

-- ─────────────────────────────────────────────────────────────────────────────
-- Por qué
--
-- El servicio nació sabiendo demasiado. `recipient_id` era un partyId o un prospectId, las
-- preferencias colgaban de un partyId, y el contacto se reconstruía con un join de tres pasos
-- —prospecto → solicitud → cuenta— que sólo tiene sentido para quien pide un préstamo. Notificar a
-- un asesor externo, a una distribuidora o a un empleado no cabía en ese modelo: había que
-- enseñarle al servicio un journey nuevo cada vez.
--
-- Un notificador no tiene por qué saber a quién notifica. Lo que necesita es un destinatario con
-- una dirección y una preferencia; **quién es esa entidad es asunto de quien la registra**. Por eso
-- `recipient_type` es texto libre y este servicio no ramifica sobre su valor en ninguna parte: si
-- lo hiciera, volvería a acoplarse a los dominios que hoy existen y quedaría igual de cerrado ante
-- los que no existen todavía.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE notifications.notification_recipients (
    recipient_type VARCHAR(40)  NOT NULL,
    recipient_id   UUID         NOT NULL,
    display_name   VARCHAR(200),
    phone          VARCHAR(20),
    email          VARCHAR(254),
    push_token     VARCHAR(500),
    locale         VARCHAR(10)  NOT NULL DEFAULT 'es-MX',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_notification_recipients PRIMARY KEY (recipient_type, recipient_id),
    -- Un destinatario sin ninguna vía de contacto no es un destinatario. Se rechaza al registrarlo
    -- y no al enviarle: descubrirlo en el envío convierte un error de alta en un mensaje perdido.
    CONSTRAINT chk_recipient_reachable CHECK (
        phone IS NOT NULL OR email IS NOT NULL OR push_token IS NOT NULL)
);

-- ── Los registros y las preferencias ganan el tipo ──────────────────────────
-- El default 'PARTY' vale sólo para lo ya escrito: todo lo existente es del journey del cliente.
-- Las filas nuevas lo declaran explícitamente.
ALTER TABLE notifications.notification_records
    ADD COLUMN recipient_type VARCHAR(40) NOT NULL DEFAULT 'PARTY';

ALTER TABLE notifications.notification_preferences
    ADD COLUMN recipient_type VARCHAR(40) NOT NULL DEFAULT 'PARTY';

-- La preferencia dejaba de tener sentido con una sola llave: dos entidades de tipos distintos
-- pueden compartir id sin ser la misma persona.
ALTER TABLE notifications.notification_preferences
    DROP CONSTRAINT IF EXISTS pk_notification_preferences;
ALTER TABLE notifications.notification_preferences
    ADD CONSTRAINT pk_notification_preferences PRIMARY KEY (recipient_type, party_id);

-- ── El tipo de evento deja de ser un enum cerrado ───────────────────────────
--
-- `event_type` era un enum de Java grabado como texto, y políticas y plantillas lo usaban de llave.
-- Con eso, cada emisor nuevo obligaba a recompilar y redesplegar el notificador para agregar su
-- caso al enum — el acoplamiento más caro de los tres, porque convierte «mandar un aviso» en un
-- cambio de código en un servicio ajeno.
--
-- `event_key` es texto libre. El enum sobrevive como catálogo de las claves del journey de crédito
-- que los listeners internos ya usan; deja de ser el tipo de almacenamiento.
ALTER TABLE notifications.notification_policies   ADD COLUMN event_key VARCHAR(80);
ALTER TABLE notifications.notification_templates  ADD COLUMN event_key VARCHAR(80);
ALTER TABLE notifications.notification_records    ADD COLUMN event_key VARCHAR(80);

UPDATE notifications.notification_policies  SET event_key = event_type WHERE event_key IS NULL;
UPDATE notifications.notification_templates SET event_key = event_type WHERE event_key IS NULL;
UPDATE notifications.notification_records   SET event_key = event_type WHERE event_key IS NULL;

ALTER TABLE notifications.notification_policies  ALTER COLUMN event_key SET NOT NULL;
ALTER TABLE notifications.notification_templates ALTER COLUMN event_key SET NOT NULL;
ALTER TABLE notifications.notification_records   ALTER COLUMN event_key SET NOT NULL;

-- `event_type` se queda nullable: las claves que registren otros emisores no tienen enum que poner,
-- y forzarlas a inventarse uno sería reabrir la puerta que este changeset cierra.
ALTER TABLE notifications.notification_policies  ALTER COLUMN event_type DROP NOT NULL;
ALTER TABLE notifications.notification_templates ALTER COLUMN event_type DROP NOT NULL;
ALTER TABLE notifications.notification_records   ALTER COLUMN event_type DROP NOT NULL;

-- ── Índices ─────────────────────────────────────────────────────────────────
-- El feed: lo no leído de una entidad, lo más reciente primero. Es la consulta de la campana y
-- corre en cada carga de pantalla.
CREATE INDEX idx_notification_records_recipient_feed
    ON notifications.notification_records (recipient_type, recipient_id, sent_at DESC);

CREATE INDEX idx_notification_records_unread
    ON notifications.notification_records (recipient_type, recipient_id)
    WHERE read_at IS NULL;

-- El unique de la 002 llaveaba por `event_type`, que ya no es la llave y ahora admite NULL: dos
-- políticas de emisores distintos podrían chocar o colarse. Se reemplaza por su equivalente sobre
-- `event_key`.
DROP INDEX IF EXISTS notifications.idx_notification_policies_active;

CREATE UNIQUE INDEX uq_notification_policies_active_key
    ON notifications.notification_policies (event_key, value_tier)
    WHERE status = 'ACTIVE';

-- Igual que con las políticas: el unique de la 003 llaveaba por `event_type`, que ya admite NULL.
-- Se reemplaza por su equivalente sobre `event_key`, que es la llave real.
DROP INDEX IF EXISTS notifications.idx_notification_templates_key;

CREATE UNIQUE INDEX uq_notification_templates_key
    ON notifications.notification_templates (event_key, channel, locale);
