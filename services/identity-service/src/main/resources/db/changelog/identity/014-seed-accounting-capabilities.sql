-- liquibase formatted sql

-- changeset identity:014 author:fintech
-- Las dos capacidades del módulo de contabilidad.
--
-- Se separan en ver y cerrar porque son actos distintos. Leer el mayor es una consulta; cerrar un
-- período —o correr la facturación, que emite CFDI— mueve el corte contable y no se deshace sin
-- dejar rastro. Con una sola capacidad, auditar implicaría poder mover el corte de lo que se está
-- auditando, que es exactamente lo que una separación de funciones existe para impedir.
--
-- `FINANCE` era hasta ahora un rol sin nada propio: veía tablero, cartera y estructura como
-- cualquiera. Éste es su módulo.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    ('ADMIN',   'accounting.view',  'seed'),
    ('ADMIN',   'accounting.close', 'seed'),
    ('FINANCE', 'accounting.view',  'seed'),
    ('FINANCE', 'accounting.close', 'seed'),
    -- Auditoría lee y no cierra.
    ('AUDITOR', 'accounting.view',  'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
