--liquibase formatted sql
--changeset closing:019-dias-inhabiles-banxico
--comment The bank holidays of 2026 and 2027: the days banks do not operate in Mexico besides weekends.

-- The business calendar only stored exceptions and none had been loaded: every holiday counted as a business day.
-- A statement's due date fell on 16 September and on 2 November (20 in the 3 Nov demo) and loans fell due on Holy
-- Thursday. These are the days the CNBV publishes each December in the DOF as the ones credit institutions do not
-- operate (the calendar Banxico's payment systems follow); each year's list is loaded when it is published.
INSERT INTO closing.calendar_days (calendar_code, day, is_business, label) VALUES
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
