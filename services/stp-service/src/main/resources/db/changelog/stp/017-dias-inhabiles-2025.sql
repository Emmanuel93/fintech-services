--liquibase formatted sql
--changeset stp-service:017-dias-inhabiles-2025
--comment The bank holidays of 2025 in the connector's copy of the calendar: SPEI dated in 2025 takes its operation date with them.

-- Closing owns the calendar and publishes it (closing.business-calendar-day); this copy is seeded with the same days so
-- the first order of a fresh environment is never dated on a holiday while the copy is still arriving. A history that
-- starts on 1 January 2025 (the end-to-end distributor suite) needs that year. 2 November fell on a Sunday.
INSERT INTO stp.business_calendar_days (calendar_code, day, is_business, label) VALUES
    ('MX', '2025-01-01', FALSE, 'Año Nuevo'),
    ('MX', '2025-02-03', FALSE, 'Día de la Constitución'),
    ('MX', '2025-03-17', FALSE, 'Natalicio de Benito Juárez'),
    ('MX', '2025-04-17', FALSE, 'Jueves Santo'),
    ('MX', '2025-04-18', FALSE, 'Viernes Santo'),
    ('MX', '2025-05-01', FALSE, 'Día del Trabajo'),
    ('MX', '2025-09-16', FALSE, 'Día de la Independencia'),
    ('MX', '2025-11-17', FALSE, 'Revolución Mexicana'),
    ('MX', '2025-12-12', FALSE, 'Día del empleado bancario'),
    ('MX', '2025-12-25', FALSE, 'Navidad')
ON CONFLICT DO NOTHING;
