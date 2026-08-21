--liquibase formatted sql

--changeset collections-service:010-instrument-communications
-- Instrumentación de comunicaciones: quién contactó, por dónde, y cuándo se calla.
--
-- La tabla de contactos guardaba sólo lo que un agente capturaba a mano, mientras notifications
-- llevaba aparte su historial de lo enviado. Eran dos bitácoras que nadie cruzaba, y la del caso
-- —la que se audita— no tenía los mensajes: un gestor abría un expediente sin saber que el sistema
-- ya había mandado tres avisos ese día.
--
-- ORIGIN es lo que separa las dos cosas, y decide algo más que una etiqueta: el tope diario de
-- CONDUSEF cuenta sólo los MANUAL. Si la cadencia automática consumiera el cupo, el agente llegaría
-- al caso sin intentos disponibles por mensajes que él no mandó, y un límite pensado para proteger
-- al cliente acabaría impidiendo la única llamada capaz de resolverle el problema.
--
-- Todo nulable: lo ya registrado es anterior a la distinción y no se puede reconstruir. Se rellena
-- ORIGIN='MANUAL' porque es lo que efectivamente era —hasta hoy sólo escribían agentes—, y eso sí
-- es un hecho, no una suposición.
ALTER TABLE collections.contact_attempts
    ADD COLUMN origin          VARCHAR(16),
    ADD COLUMN notification_id UUID,
    ADD COLUMN dunning_step    INTEGER;

UPDATE collections.contact_attempts SET origin = 'MANUAL' WHERE origin IS NULL;

ALTER TABLE collections.contact_attempts
    ALTER COLUMN origin SET NOT NULL;

-- El agente deja de ser obligatorio: un mensaje automático no tiene persona a quien atribuirle el
-- acto, y llenar la columna con un usuario «SISTEMA» haría que la bitácora afirmara que alguien
-- llamó.
ALTER TABLE collections.contact_attempts
    ALTER COLUMN agent_id DROP NOT NULL;

-- El tope se resuelve con esta consulta varias veces al día y filtra por las tres columnas.
CREATE INDEX contact_attempts_case_origin_time_idx
    ON collections.contact_attempts (case_id, origin, attempted_at DESC);

--changeset collections-service:010-widen-contact-result
-- El CHECK de la 004 sólo admite los cuatro desenlaces de una llamada, y ahora la tabla también
-- guarda mensajes: un WhatsApp entregado no «contestó».
--
-- Se descubrió al probarlo de punta a punta: el servicio registraba el contacto en el log y la
-- inserción reventaba al hacer commit, así que el caso quedaba sin el apunte mientras las trazas
-- decían que sí se había guardado. Es exactamente la clase de fallo que una bitácora no puede
-- tener —afirmar que consta algo que no consta—, y por eso el constraint se amplía en vez de
-- quitarse: sigue habiendo un conjunto cerrado de valores válidos.
ALTER TABLE collections.contact_attempts
    DROP CONSTRAINT IF EXISTS chk_contact_attempts_result;

ALTER TABLE collections.contact_attempts
    ADD CONSTRAINT chk_contact_attempts_result CHECK (result IN
        ('ANSWERED','NO_ANSWER','WRONG_NUMBER','PROMISE_MADE',
         'DELIVERED','READ','FAILED'));

ALTER TABLE collections.contact_attempts
    ADD CONSTRAINT chk_contact_attempts_origin CHECK (origin IN ('MANUAL','AUTOMATIC'));

-- Un contacto de agente sin agente sería un registro sin responsable; uno automático con agente
-- atribuiría a una persona algo que hizo el sistema. La base lo impide en vez de confiar en que
-- todos los caminos de escritura se acuerden.
ALTER TABLE collections.contact_attempts
    ADD CONSTRAINT chk_contact_attempts_agent CHECK (
        (origin = 'MANUAL'    AND agent_id IS NOT NULL) OR
        (origin = 'AUTOMATIC' AND agent_id IS NULL));

--changeset collections-service:010-communication-holds
-- Los periodos en que la cobranza automática de un caso está callada.
--
-- Se guarda como historial y no como bandera en el caso porque la pregunta que hay que poder
-- contestar es «por qué no se le escribió a esta persona entre el 3 y el 12», y una bandera sólo
-- dice el estado de hoy. Un caso acumula varios frenos a lo largo de su vida y pueden solaparse;
-- vale el más lejano.
--
-- RELEASED_AT distingue dos finales que no significan lo mismo: un freno que expiró solo es un
-- plazo cumplido, y uno liberado antes de tiempo es una promesa rota o una decisión de alguien.
CREATE TABLE collections.communication_holds (
    hold_id      UUID         PRIMARY KEY,
    case_id      UUID         NOT NULL,
    reason       VARCHAR(32)  NOT NULL,
    held_until   TIMESTAMPTZ  NOT NULL,
    source_id    UUID,
    released_at  TIMESTAMPTZ,
    released_by  VARCHAR(255),
    release_note VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL
);

-- La corrida diaria de la cadencia pregunta una sola vez qué casos están silenciados, en vez de
-- una consulta por caso. Este índice es el que hace que esa consulta sea una y no varios miles.
CREATE INDEX communication_holds_active_idx
    ON collections.communication_holds (case_id, held_until DESC)
    WHERE released_at IS NULL;

CREATE INDEX communication_holds_case_idx
    ON collections.communication_holds (case_id, created_at DESC);
