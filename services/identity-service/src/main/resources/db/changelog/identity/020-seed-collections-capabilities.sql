-- liquibase formatted sql

-- changeset identity:020 author:fintech
-- Cobranza: gestionar no es autorizar.
--
-- Hasta ahora todo /collections/** pedía portfolio.view, la capacidad de quien mira cartera. Con
-- ella un ejecutivo podía autorizar un quebranto o un convenio, y el nombre de quien autorizaba
-- venía escrito en la petición. Autorizar condona deuda y la saca del balance: es una decisión con
-- costo, y la toma el comité. El dominio exige además que quien propone un convenio no lo autorice.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    -- Gestionar: contacto, promesa, proponer convenio, registrar la respuesta del deudor, pedir quebranto.
    ('ADMIN',             'collections.manage',    'seed'),
    ('COLLECTIONS_AGENT', 'collections.manage',    'seed'),
    ('OPS_SUPERVISOR',    'collections.manage',    'seed'),
    -- Autorizar un convenio o un quebranto.
    ('ADMIN',             'collections.authorize', 'seed'),
    ('COMMITTEE',         'collections.authorize', 'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
