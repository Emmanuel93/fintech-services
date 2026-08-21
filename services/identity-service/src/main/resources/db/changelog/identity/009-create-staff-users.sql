-- liquibase formatted sql

-- changeset identity:009 author:fintech
-- Personal con acceso al backoffice. Separado de identity.credentials a propósito:
-- aquella cuelga de un party_id (un cliente), esta de la institución.
CREATE TABLE identity.staff_users (
    staff_user_id        UUID            NOT NULL DEFAULT gen_random_uuid(),
    email                VARCHAR(255)    NOT NULL,
    full_name            VARCHAR(255)    NOT NULL,
    employee_type        VARCHAR(30)     NOT NULL,
    distributor_party_id UUID,
    password_hash        VARCHAR(255)    NOT NULL,
    status               VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    failed_attempts      INT             NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    last_login_ip        VARCHAR(45),
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_staff_users PRIMARY KEY (staff_user_id),
    CONSTRAINT uq_staff_users_email UNIQUE (email),
    CONSTRAINT chk_staff_employee_type CHECK (employee_type IN ('INTERNO', 'COLABORADOR_EMPRESARIAL')),
    CONSTRAINT chk_staff_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LOCKED', 'DISABLED')),
    -- Un colaborador empresarial siempre pertenece a un distribuidor; el interno nunca.
    CONSTRAINT chk_staff_distributor CHECK (
        (employee_type = 'COLABORADOR_EMPRESARIAL' AND distributor_party_id IS NOT NULL)
        OR (employee_type = 'INTERNO' AND distributor_party_id IS NULL)
    )
);

CREATE INDEX ix_staff_users_status ON identity.staff_users (status);
CREATE INDEX ix_staff_users_distributor ON identity.staff_users (distributor_party_id)
    WHERE distributor_party_id IS NOT NULL;

-- changeset identity:009-roles author:fintech
-- Colección de valores del agregado StaffUser, no una entidad con vida propia.
CREATE TABLE identity.staff_user_roles (
    staff_user_id UUID        NOT NULL,
    role          VARCHAR(30) NOT NULL,
    CONSTRAINT pk_staff_user_roles PRIMARY KEY (staff_user_id, role),
    CONSTRAINT fk_staff_user_roles_user FOREIGN KEY (staff_user_id)
        REFERENCES identity.staff_users (staff_user_id) ON DELETE CASCADE,
    CONSTRAINT chk_staff_role CHECK (role IN (
        'ADMIN', 'OPS_SUPERVISOR', 'CREDIT_ANALYST', 'UNDERWRITER', 'COMMITTEE',
        -- COMMERCIAL_MANAGER dirige una rama de la estructura comercial. Va junto a EXECUTIVE
        -- porque son los dos roles del canal de ventas: uno atiende su cartera y el otro dirige
        -- lo que cuelga de su nodo. El alcance no lo da el rol sino la unidad a la que la persona
        -- está adscrita, así que uno solo cubre los cuatro escalones —sucursal, zona, región,
        -- nacional— sin repetir cada regla cuatro veces en la matriz.
        'EXECUTIVE', 'COMMERCIAL_MANAGER', 'COLLECTIONS_AGENT', 'RISK_ANALYST',
        'PRODUCT_MANAGER', 'FINANCE', 'MARKETING', 'AUDITOR', 'SUPPORT'
    ))
);

CREATE INDEX ix_staff_user_roles_role ON identity.staff_user_roles (role);
