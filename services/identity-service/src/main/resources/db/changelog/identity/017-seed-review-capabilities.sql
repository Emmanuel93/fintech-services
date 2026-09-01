-- liquibase formatted sql

-- changeset identity:017 author:fintech
-- Dos capacidades que el backoffice exige y que nadie tenía.
--
-- Es exactamente lo que el changeset 015 advirtió al arreglar `beneficiaries.view`: **una ruta
-- protegida por una capacidad que nadie tiene no está protegida, está rota**, y el síntoma —403 sin
-- explicación— es indistinguible de un permiso mal configurado. Volvió a pasar con otras dos, y el
-- efecto era que dos mesas de trabajo no podían trabajar:
--
--   · `PUT /origination/applications/*/documents/*/review` — el analista no podía dictaminar un
--     documento. La bandeja se veía, el botón estaba, y guardar respondía 403 a todo el mundo,
--     incluido ADMIN.
--   · `POST /beneficiaries/placements/*/identity-review` — la mesa de KYC no podía firmar el cotejo
--     de identidad de una beneficiaria, que es la única decisión que esa mesa toma.
--
-- Quién las recibe sigue a quién ya hace ese trabajo hoy, no se inventa un reparto nuevo.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    -- Dictaminar un documento va con pedirlo: quien lo solicita es quien sabe qué esperaba ver.
    ('ADMIN',          'applications.review-documents', 'seed'),
    ('COMMITTEE',      'applications.review-documents', 'seed'),
    ('CREDIT_ANALYST', 'applications.review-documents', 'seed'),
    ('UNDERWRITER',    'applications.review-documents', 'seed'),
    -- La mesa de KYC. Es su única facultad de escritura y no incluye decidir la colocación: esa
    -- decisión es de la distribuidora y queda firmada por ella, como dice el 015.
    ('ADMIN',          'beneficiaries.review-identity', 'seed'),
    ('OPS_SUPERVISOR', 'beneficiaries.review-identity', 'seed'),
    ('SUPPORT',        'beneficiaries.review-identity', 'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
