-- liquibase formatted sql

-- changeset identity:012 author:fintech
-- CURP del empleado.
--
-- La bitácora de auditoría debe identificar plenamente a quien actúa, y para una persona física
-- eso incluye la CURP. Del cliente ya se conocía (party.parties, origination.prospects); del
-- personal no existía en ninguna parte, así que toda entrada de auditoría atribuida a un empleado
-- quedaba identificada sólo por un UUID y un correo.
--
-- Nulable: el personal dado de alta antes de esta migración no la tiene y el registro nunca se
-- borra (SU-03). Se captura en el alta a partir de aquí; para los existentes se llena cuando
-- recursos humanos la aporte.
--
-- No se declara única: dos altas de la misma persona son un error de captura que corrige quien
-- administra el personal, y hacer fallar el alta con una violación de integridad convertiría ese
-- error en un 500 sin mensaje útil.
ALTER TABLE identity.staff_users
    ADD COLUMN curp VARCHAR(18);

ALTER TABLE identity.staff_users
    ADD CONSTRAINT chk_staff_curp_format
        CHECK (curp IS NULL OR curp ~ '^[A-Z]{4}[0-9]{6}[HM][A-Z]{5}[A-Z0-9][0-9]$');

CREATE INDEX ix_staff_users_curp ON identity.staff_users (curp) WHERE curp IS NOT NULL;

-- El administrador de arranque es el único empleado que existe antes de que nadie pueda capturar
-- nada, y es con quien se navega hasta que hay plantilla: dejarlo sin CURP significaría que las
-- primeras entradas de la bitácora —las del arranque del sistema— nacen sin identificar. Valor de
-- demostración, del mismo tenor que su contraseña: rotar junto con ella.
UPDATE identity.staff_users
   SET curp = 'AAAA800101HDFDMD09'
 WHERE staff_user_id = '00000000-0000-4000-8000-000000000001'
   AND curp IS NULL;
