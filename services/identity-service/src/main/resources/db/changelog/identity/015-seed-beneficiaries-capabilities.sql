-- liquibase formatted sql

-- changeset identity:015 author:fintech
-- La capacidad de la mesa de KYC de la colocación B2B2C.
--
-- `channel-backoffice-service` ya protege `GET /beneficiaries/**` con `beneficiaries.view`, pero
-- la capacidad no estaba sembrada para ningún rol: la bandeja respondía 403 a todo el mundo,
-- incluido ADMIN. Una ruta protegida por una capacidad que nadie tiene no está protegida, está
-- rota — y el síntoma (403 sin explicación) es indistinguible de un permiso mal configurado.
--
-- Es sólo de lectura y no existe su contraparte de escritura, a propósito: verificar identidad no
-- incluye decidir la colocación. Esa decisión es de la distribuidora, queda firmada por ella con
-- su aceptación de riesgo, y ningún operador de Kredius debe poder tomarla en su nombre.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    ('ADMIN',          'beneficiaries.view', 'seed'),
    -- Quien opera la mesa: revisa que la identidad de la beneficiaria esté comprobada y persigue
    -- lo que lleva días sin moverse.
    ('OPS_SUPERVISOR', 'beneficiaries.view', 'seed'),
    ('SUPPORT',        'beneficiaries.view', 'seed'),
    -- Riesgo y comité miran la cartera colocada; auditoría lee todo lo que deja rastro.
    ('RISK_ANALYST',   'beneficiaries.view', 'seed'),
    ('COMMITTEE',      'beneficiaries.view', 'seed'),
    ('AUDITOR',        'beneficiaries.view', 'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
