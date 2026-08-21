-- liquibase formatted sql

-- changeset identity:013 author:fintech
--
-- Credencial de servicio del canal de backoffice, para que la bitácora pueda resolver quién actuó.
--
-- El canal resolvía el nombre del empleado llamando a GET /api/v1/staff/{id} **con el token del
-- propio empleado**, y ese endpoint es de ADMIN: cualquiera que no fuera administrador recibía 403
-- pidiendo su propio nombre, y su entrada de auditoría quedaba sin identificar. Auditar no puede
-- depender de los permisos del auditado — es justamente al revés.
--
-- Por eso el canal se autentica como sí mismo contra /api/v1/auth/clients/token. El rol
-- SERVICE_DIRECTORY sólo abre la lectura de un empleado por id (ver SecurityConfig); no da de alta,
-- no cambia roles, no toca contraseñas.
--
-- ⚠️  Secreto de arranque — rotar antes de exponer el backoffice:
--       clientId:     channel-backoffice-service
--       clientSecret: Backoffice-Audit#2026
--     El hash es BCrypt (cost 10). Debe coincidir con
--     fintech.identity.service-client.secret del canal (variable IDENTITY_SERVICE_CLIENT_SECRET).
--
-- Sin whitelist de IP: en despliegue el canal habla con identity por la red interna y su dirección
-- la asigna el orquestador. Una whitelist con la IP de un pod se rompe en el primer reinicio; el
-- control aquí es el secreto y el rol acotado.
INSERT INTO identity.clients (
    id, client_id, secret_hash, client_name, status, roles, created_at, updated_at
) VALUES (
    '00000000-0000-4000-8000-000000000010',
    'channel-backoffice-service',
    '$2a$10$CvkdFT9TF9k7PpmcHWpNZ.w8BwJAO9wIDwitqTchlESQwqe5LBDCC',
    'Canal de backoffice (resolución de identidad para la bitácora)',
    'ACTIVE',
    'SERVICE_DIRECTORY',
    NOW(),
    NOW()
);
