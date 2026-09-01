--liquibase formatted sql
--changeset closing:004-create-profiles
--comment Proyección eventual por cuenta. Es el desacople de cartera.

-- El cierre NO consulta cartera en línea. Mantiene su propia proyección, alimentada por los
-- eventos que cartera ya publica hoy (credit-account-activated, balance-updated,
-- delinquency-status-updated). Que la proyección sea eventual está asumido: el sello declara con
-- qué versión de saldo cerró, y lo que llegue después es extemporáneo, no un error.
CREATE TABLE closing.account_close_profiles (
    credit_account_id  UUID         NOT NULL,
    obligor_party_id   UUID         NOT NULL,
    product_id         UUID,
    product_type       VARCHAR(40)  NOT NULL,
    product_behavior   VARCHAR(20)  NOT NULL,   -- INSTALLMENT | REVOLVING
    org_unit_code      VARCHAR(40),
    status             VARCHAR(20)  NOT NULL,

    -- Lo que hace falta para DERIVAR el calendario de corte sin preguntarle a cartera.
    activated_on       DATE,
    payment_frequency  VARCHAR(20),             -- WEEKLY | BIWEEKLY | MONTHLY
    term_periods       INTEGER,
    nominal_rate       NUMERIC(12,8),
    principal_balance  NUMERIC(19,4) NOT NULL DEFAULT 0,
    total_debt         NUMERIC(19,4) NOT NULL DEFAULT 0,
    days_delinquent    INTEGER       NOT NULL DEFAULT 0,

    -- El calendario propio del cierre.
    current_cycle      INTEGER       NOT NULL DEFAULT 0,
    next_cutoff_date   DATE,
    next_close_date    DATE,

    -- Frescura de la proyección: con qué versión se vio por última vez esta cuenta.
    last_balance_version BIGINT      NOT NULL DEFAULT -1,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_account_close_profiles PRIMARY KEY (credit_account_id),
    CONSTRAINT chk_profile_behavior CHECK (product_behavior IN ('INSTALLMENT','REVOLVING'))
);

-- El índice de la pregunta que el cierre hace todos los días: ¿a qué cuentas les toca hoy?
CREATE INDEX idx_profiles_cutoff_due ON closing.account_close_profiles (next_cutoff_date)
    WHERE next_cutoff_date IS NOT NULL;
CREATE INDEX idx_profiles_close_due  ON closing.account_close_profiles (next_close_date, status);
CREATE INDEX idx_profiles_product    ON closing.account_close_profiles (product_type, status);
