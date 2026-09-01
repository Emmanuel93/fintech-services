--liquibase formatted sql
--changeset creditportfolio:021-relief-programs
--comment BK-32 · programas de apoyo por contingencia. Se implementan como reestructura.

-- **Por qué reestructura y no un modo regulatorio especial.** Correr el monto a la siguiente fecha
-- de pago, que no genere saldo por cobrar en ese momento, y decidir a qué créditos se otorga, es
-- jurídicamente un diferimiento de pagos: una reestructura. El camino alterno —un tratamiento
-- contable especial— depende de una autorización que puede no estar vigente cuando la contingencia
-- ocurra, que es justo cuando hay que actuar rápido.
--
-- **El costo se asume con los ojos abiertos:** marca forborne, fuerza piso IFRS-9 STAGE_2 y reinicia
-- el reloj de cura. La reserva SUBE. A cambio, el historial del cliente ante el buró no se degrada:
-- `collections` sólo reporta WRITE_OFF y QUITA_PARCIAL — la reestructura no se reporta.
CREATE TABLE credit_portfolio.relief_programs (
    relief_program_id   UUID          NOT NULL DEFAULT gen_random_uuid(),
    name                VARCHAR(120)  NOT NULL,
    reason              VARCHAR(20)   NOT NULL,
    -- INTEGER y no SMALLINT: con `ddl-auto: validate`, SMALLINT exige `short` del lado Java.
    deferred_periods    INTEGER       NOT NULL,
    valid_from          DATE          NOT NULL,
    valid_to            DATE          NOT NULL,

    -- Elegibilidad. Todo nulo = sin ese filtro. Se combinan con AND.
    product_code        VARCHAR(20),
    origin_unit_code    VARCHAR(40),
    region              VARCHAR(60),
    max_days_delinquent INTEGER,
    -- «Al corriente a esta fecha». Evita que el apoyo tape mora previa: sin fecha de corte, un
    -- programa anunciado hoy alcanzaría a quien dejó de pagar al enterarse de que venía.
    eligibility_cutoff_date DATE      NOT NULL,

    accrual_during_relief VARCHAR(8)  NOT NULL DEFAULT 'ACCRUES',

    -- Maker-checker, como `configuration-service`. Quien lo propone no lo autoriza.
    status              VARCHAR(12)   NOT NULL DEFAULT 'PROPOSED',
    proposed_by         VARCHAR(80)   NOT NULL,
    approved_by         VARCHAR(80),
    approved_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_relief_programs PRIMARY KEY (relief_program_id),
    CONSTRAINT chk_relief_reason CHECK (reason IN ('SANITARY','NATURAL_DISASTER','SECURITY','OTHER')),
    CONSTRAINT chk_relief_accrual CHECK (accrual_during_relief IN ('ACCRUES','WAIVED')),
    CONSTRAINT chk_relief_status CHECK (status IN ('PROPOSED','APPROVED','REJECTED','CLOSED')),
    CONSTRAINT chk_relief_periods CHECK (deferred_periods > 0),
    CONSTRAINT chk_relief_vigencia CHECK (valid_to >= valid_from)
);

-- El expediente por cuenta apoyada (BK-36). Es lo que permite sustentar el apoyo ante el cliente y
-- ante una revisión: qué programa, qué cuotas se corrieron, de qué fecha a qué fecha.
CREATE TABLE credit_portfolio.relief_enrollments (
    enrollment_id      UUID          NOT NULL DEFAULT gen_random_uuid(),
    relief_program_id  UUID          NOT NULL,
    credit_account_id  UUID          NOT NULL,
    days_delinquent_at_enrollment INTEGER NOT NULL,
    installments_moved INTEGER       NOT NULL DEFAULT 0,
    first_due_before   DATE,
    first_due_after    DATE,
    enrolled_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    released_at        TIMESTAMPTZ,

    CONSTRAINT pk_relief_enrollments PRIMARY KEY (enrollment_id),
    -- Una cuenta entra UNA vez a cada programa. Sin esto, reejecutar el alta masiva —que es lo que
    -- pasa cuando alguien reintenta un lote que pareció fallar— correría los vencimientos otra vez.
    CONSTRAINT uq_relief_enrollment UNIQUE (relief_program_id, credit_account_id),
    CONSTRAINT fk_relief_enrollment_program FOREIGN KEY (relief_program_id)
        REFERENCES credit_portfolio.relief_programs (relief_program_id)
);

CREATE INDEX idx_relief_enrollments_cuenta
    ON credit_portfolio.relief_enrollments (credit_account_id)
    WHERE released_at IS NULL;
