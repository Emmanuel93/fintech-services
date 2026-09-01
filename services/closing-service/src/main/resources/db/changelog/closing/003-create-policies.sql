--liquibase formatted sql
--changeset closing:003-create-policies
--comment Política de cierre por producto, versionada. Es lo que hoy son 14 crones constantes.

CREATE TABLE closing.close_cycle_policies (
    policy_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    scope_type     VARCHAR(12)  NOT NULL,
    scope_value    VARCHAR(60),              -- productId, productType, o NULL para GLOBAL
    version        INTEGER      NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    effective_date DATE         NOT NULL,
    calendar_code  VARCHAR(20)  NOT NULL,

    -- Fases que aplican a este producto, en orden.
    phases         VARCHAR(400) NOT NULL,

    -- La convención de devengo. NO es un detalle: el plan de pagos de cartera reparte el interés
    -- con tasa/periodosPorAño (30/360 implícito) y el devengo diario usa tasa/360 por día natural.
    -- Sobre $20,000 al 32% eso son $533.33 en el plan contra $551.18 devengados en marzo y $497.84
    -- en febrero. Ninguna convención está mal; lo que estaba mal era que no estuviera declarada.
    accrual_basis  VARCHAR(16)  NOT NULL DEFAULT 'ACTUAL_360',

    -- De dónde sale la fecha de corte de una cuenta de este producto.
    --   INSTALLMENT_DUE_DATE   → la cadencia del plan (no revolvente); el cierre la DERIVA y la
    --                            persiste en cutoff_schedules, no la consulta a cartera
    --   CYCLE_FROM_ACTIVATION  → ciclo mensual anclado al día de activación (revolvente)
    --   DAY_OF_MONTH           → día fijo del mes, en cutoff_day
    --   NONE                   → el producto no tiene corte
    cutoff_rule    VARCHAR(24)  NOT NULL DEFAULT 'NONE',
    cutoff_day     SMALLINT,
    payment_due_offset_days SMALLINT NOT NULL DEFAULT 0,
    non_business_day_shift  VARCHAR(8) NOT NULL DEFAULT 'NEXT',

    -- Conciliación: cuánto se tolera antes de bloquear el sello, y qué hacer si se excede.
    reconcile_tolerance NUMERIC(19,4) NOT NULL DEFAULT 0,
    on_unreconciled     VARCHAR(20)   NOT NULL DEFAULT 'BLOCK_SEAL',

    -- Operación del reparto.
    batch_size     INTEGER      NOT NULL DEFAULT 500,
    lease_seconds  INTEGER      NOT NULL DEFAULT 300,
    window_minutes INTEGER      NOT NULL DEFAULT 120,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_close_cycle_policies PRIMARY KEY (policy_id),
    CONSTRAINT uq_policy_scope_version UNIQUE (scope_type, scope_value, version),
    CONSTRAINT chk_policy_scope  CHECK (scope_type IN ('GLOBAL','PRODUCT_TYPE','PRODUCT')),
    CONSTRAINT chk_policy_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT chk_policy_basis  CHECK (accrual_basis IN ('ACTUAL_360','THIRTY_360','ACTUAL_365')),
    CONSTRAINT chk_policy_cutoff CHECK (cutoff_rule IN
        ('NONE','INSTALLMENT_DUE_DATE','CYCLE_FROM_ACTIVATION','DAY_OF_MONTH')),
    CONSTRAINT chk_policy_shift  CHECK (non_business_day_shift IN ('NEXT','PREV','NONE')),
    CONSTRAINT chk_policy_unrec  CHECK (on_unreconciled IN ('BLOCK_SEAL','ALERT_AND_CONTINUE')),
    -- DAY_OF_MONTH sin día es una política que no se puede ejecutar.
    CONSTRAINT chk_policy_cutoff_day CHECK (cutoff_rule <> 'DAY_OF_MONTH' OR cutoff_day BETWEEN 1 AND 28),
    CONSTRAINT fk_policy_calendar FOREIGN KEY (calendar_code)
        REFERENCES closing.business_calendars (calendar_code)
);

-- Una sola política vigente por alcance. Sin esto, dos ACTIVE del mismo producto harían que el
-- cierre dependiera del orden en que la base devuelve las filas.
CREATE UNIQUE INDEX uq_policy_active
    ON closing.close_cycle_policies (scope_type, COALESCE(scope_value, ''))
    WHERE status = 'ACTIVE';
