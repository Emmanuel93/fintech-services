-- liquibase formatted sql

-- changeset identity:010 author:fintech
--
-- Administrador de arranque. Sin él no hay forma de crear el primer empleado:
-- POST /api/v1/staff exige rol ADMIN, y nadie lo tiene todavía.
--
-- ⚠️  Credenciales de arranque — rotar antes de exponer el backoffice:
--       usuario:    admin@kredius.mx
--       contraseña: Backoffice#2026
--     El hash es BCrypt (cost 10). Cambiarla con PUT /api/v1/staff/{id}/password
--     invalida este valor; este changeset no vuelve a ejecutarse.
INSERT INTO identity.staff_users (
    staff_user_id, email, full_name, employee_type, password_hash, status
) VALUES (
    '00000000-0000-4000-8000-000000000001',
    'admin@kredius.mx',
    'Administrador de arranque',
    'INTERNO',
    '$2y$10$6Kc42Hwn5V5CPwTSw/Qq0uS.4hCjecFu41LeEZTMxIbMZWu5wCcVa',
    'ACTIVE'
);

INSERT INTO identity.staff_user_roles (staff_user_id, role) VALUES
    ('00000000-0000-4000-8000-000000000001', 'ADMIN');
