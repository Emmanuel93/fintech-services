--liquibase formatted sql
--changeset closing:002-create-calendars
--comment Calendario de negocio: sustituye a LocalDate.now() dentro de cada job.

-- Hasta aquí, la fecha de un cierre era el reloj del pod que lo corría. Sin calendario no hay
-- forma de contestar "¿esto es día hábil?", ni de correr un corte que cae en domingo, ni de
-- reproducir el cierre del día 15 el día 20 — que es lo que exige cualquier reproceso.
CREATE TABLE closing.business_calendars (
    calendar_code VARCHAR(20)  NOT NULL,
    zone_id       VARCHAR(40)  NOT NULL,   -- la zona de NEGOCIO, no la del contenedor
    description   VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_business_calendars PRIMARY KEY (calendar_code)
);

-- Sólo se guardan las EXCEPCIONES y los fines de semana materializados del horizonte cargado.
-- Guardar día por día es deliberado: los festivos de México no se derivan de una regla estable
-- (hay traslados por decreto), así que una tabla es más honesta que un algoritmo que miente un año.
CREATE TABLE closing.calendar_days (
    calendar_code VARCHAR(20) NOT NULL,
    day           DATE        NOT NULL,
    is_business   BOOLEAN     NOT NULL,
    label         VARCHAR(80),
    CONSTRAINT pk_calendar_days PRIMARY KEY (calendar_code, day),
    CONSTRAINT fk_calendar_days_calendar FOREIGN KEY (calendar_code)
        REFERENCES closing.business_calendars (calendar_code) ON DELETE CASCADE
);

CREATE INDEX idx_calendar_days_business ON closing.calendar_days (calendar_code, day)
    WHERE is_business = FALSE;
