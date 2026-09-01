-- liquibase formatted sql

-- changeset identity:016 author:fintech
-- La capacidad de los programas de apoyo por contingencia.
--
-- Un programa de apoyo corre los vencimientos de un segmento entero de la cartera y marca esas
-- cuentas como reestructuradas: sube la reserva por el paso a STAGE_2. No es una consulta, es una
-- decisión con costo, y por eso no cuelga de `portfolio.view`.
--
-- Se separa en dos por la misma razón que existe el maker-checker: **simular el padrón no es
-- otorgarlo**. Ver a cuántas cuentas alcanzaría lo puede hacer quien vigila —riesgo, comité,
-- auditoría— sin poder mover un solo vencimiento. Otorgarlo es de riesgo y administración.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    -- Ver el padrón: quién alcanzaría el apoyo, sin tocar nada.
    ('ADMIN',         'portfolio.relief-view',  'seed'),
    ('RISK_ANALYST',  'portfolio.relief-view',  'seed'),
    ('COMMITTEE',     'portfolio.relief-view',  'seed'),
    ('AUDITOR',       'portfolio.relief-view',  'seed'),
    -- Proponer, autorizar y otorgar. El dominio exige además que quien propone no autorice, así
    -- que dos personas con esta capacidad siguen haciendo falta para que un apoyo se otorgue.
    ('ADMIN',         'portfolio.relief-grant', 'seed'),
    ('RISK_ANALYST',  'portfolio.relief-grant', 'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
