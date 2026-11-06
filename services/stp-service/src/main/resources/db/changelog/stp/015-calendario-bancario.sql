--liquibase formatted sql
--changeset stp-service:015-calendario-bancario
--comment The bank calendar, as closing publishes it: the days that differ from the Monday-to-Friday rule.

-- The connector dates every SPEI movement with its operation date (fechaOperacion): the business day on which the
-- bank books it. That needs the bank holidays. Closing owns the calendar and publishes it on
-- closing.business-calendar-day; this copy is seeded with the same days so the first order of a fresh environment
-- is never dated on a holiday while the copy is still arriving, and is kept up to date by that topic.
CREATE TABLE IF NOT EXISTS stp.business_calendar_days (
    calendar_code  VARCHAR(20)  NOT NULL,
    day            DATE         NOT NULL,
    is_business    BOOLEAN      NOT NULL,
    label          VARCHAR(80),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_business_calendar_days PRIMARY KEY (calendar_code, day)
);

INSERT INTO stp.business_calendar_days (calendar_code, day, is_business, label) VALUES
    ('MX', '2026-01-01', FALSE, 'Año Nuevo'),
    ('MX', '2026-02-02', FALSE, 'Día de la Constitución'),
    ('MX', '2026-03-16', FALSE, 'Natalicio de Benito Juárez'),
    ('MX', '2026-04-02', FALSE, 'Jueves Santo'),
    ('MX', '2026-04-03', FALSE, 'Viernes Santo'),
    ('MX', '2026-05-01', FALSE, 'Día del Trabajo'),
    ('MX', '2026-09-16', FALSE, 'Día de la Independencia'),
    ('MX', '2026-11-02', FALSE, 'Día de Muertos'),
    ('MX', '2026-11-16', FALSE, 'Revolución Mexicana'),
    ('MX', '2026-12-12', FALSE, 'Día del empleado bancario'),
    ('MX', '2026-12-25', FALSE, 'Navidad'),
    ('MX', '2027-01-01', FALSE, 'Año Nuevo'),
    ('MX', '2027-02-01', FALSE, 'Día de la Constitución'),
    ('MX', '2027-03-15', FALSE, 'Natalicio de Benito Juárez'),
    ('MX', '2027-03-25', FALSE, 'Jueves Santo'),
    ('MX', '2027-03-26', FALSE, 'Viernes Santo'),
    ('MX', '2027-05-01', FALSE, 'Día del Trabajo'),
    ('MX', '2027-09-16', FALSE, 'Día de la Independencia'),
    ('MX', '2027-11-02', FALSE, 'Día de Muertos'),
    ('MX', '2027-11-15', FALSE, 'Revolución Mexicana'),
    ('MX', '2027-12-12', FALSE, 'Día del empleado bancario'),
    ('MX', '2027-12-25', FALSE, 'Navidad')
ON CONFLICT DO NOTHING;
