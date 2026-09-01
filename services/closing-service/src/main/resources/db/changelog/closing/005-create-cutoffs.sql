--liquibase formatted sql
--changeset closing:005-create-cutoffs
--comment El calendario de corte, propiedad del cierre.

-- **Por qué el cierre tiene su propio calendario y no lee `installments.due_date` de cartera.**
--
-- Para un producto no revolvente la cadencia coincide con el vencimiento de la cuota, así que la
-- tentación es consultarlo. No se hace, por tres razones:
--
--   1. Leer cartera en línea durante la ventana de cierre rompe el aislamiento — es justo lo que
--      hoy hace que el barrido nocturno compita con la API del backoffice.
--   2. El corte es una decisión de POLÍTICA, no del plan: un producto puede cortar N días antes
--      del vencimiento, o correrse si cae inhábil. El plan de pagos no sabe nada de eso.
--   3. Un corte sellado es inmutable. Si cartera regenera el calendario por una reestructura, el
--      corte ya emitido no puede cambiar retroactivamente: el ajuste va al ciclo siguiente.
--
-- El cierre DERIVA el calendario de la política del producto y de lo que aprendió por evento
-- (activación, cadencia, plazo), y lo PERSISTE aquí. Cartera sigue siendo dueña del plan de pagos
-- de cara al cliente; el cierre es dueño de cuándo cierra.
CREATE TABLE closing.cutoff_schedules (
    credit_account_id UUID          NOT NULL,
    cycle_number      INTEGER       NOT NULL,
    cutoff_date       DATE          NOT NULL,
    payment_due_date  DATE          NOT NULL,
    status            VARCHAR(12)   NOT NULL DEFAULT 'SCHEDULED',

    -- Se llenan al sellar el corte; nulos mientras está SCHEDULED.
    balance_at_cutoff   NUMERIC(19,4),
    principal_at_cutoff NUMERIC(19,4),
    interest_at_cutoff  NUMERIC(19,4),
    penalty_at_cutoff   NUMERIC(19,4),
    amount_due          NUMERIC(19,4),
    minimum_payment     NUMERIC(19,4),
    movement_count      INTEGER,
    sealed_at           TIMESTAMPTZ,
    propagated_at       TIMESTAMPTZ,

    CONSTRAINT pk_cutoff_schedules PRIMARY KEY (credit_account_id, cycle_number),
    CONSTRAINT chk_cutoff_status CHECK (status IN ('SCHEDULED','SEALED','PROPAGATED','SKIPPED')),
    -- La fecha límite nunca puede ser anterior al corte.
    CONSTRAINT chk_cutoff_due_after CHECK (payment_due_date >= cutoff_date)
);

-- La consulta diaria: ¿qué cortes caen hoy?
CREATE INDEX idx_cutoff_due_today ON closing.cutoff_schedules (cutoff_date, status)
    WHERE status = 'SCHEDULED';
