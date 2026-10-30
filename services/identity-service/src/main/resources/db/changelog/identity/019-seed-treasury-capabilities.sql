-- liquibase formatted sql

-- changeset identity:019 author:fintech
-- Bancos / Tesorería: consultar las cuentas propias, las rutas de pago y cada desembolso con su
-- orden STP y su traza.
--
-- Hasta ahora esa pregunta —«¿por qué cuenta salió este pago y en qué eslabón va?»— sólo se podía
-- contestar abriendo cuatro servicios por la red interna. La pantalla es de sólo lectura: dar de
-- alta una CLABE ordenante o apuntarle una ruta sigue siendo ADMIN en banking, porque decide por
-- dónde sale el dinero.
--
-- La recibe quien ya mira el dinero: tesorería (FINANCE), quien opera los pagos día a día
-- (OPS_SUPERVISOR) y el auditor, que lee todo y no mueve nada.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    ('ADMIN',          'treasury.view', 'seed'),
    ('OPS_SUPERVISOR', 'treasury.view', 'seed'),
    ('FINANCE',        'treasury.view', 'seed'),
    ('AUDITOR',        'treasury.view', 'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
