--liquibase formatted sql
--changeset closing:008-create-reconciliation
--comment Conciliación a tres puntas: cartera ↔ contabilidad ↔ bancos.

-- No hay servicio de bancos en la plataforma, así que el cierre es el que cierra ese hueco. Aquí
-- viven las tres conciliaciones y sus hallazgos; la posición bancaria en sí la produce
-- bank-reconciliation-service y llega por evento.
CREATE TABLE closing.reconciliation_findings (
    finding_id     UUID          NOT NULL DEFAULT gen_random_uuid(),
    business_date  DATE          NOT NULL,
    check_type     VARCHAR(28)   NOT NULL,
    scope_key      VARCHAR(60)   NOT NULL DEFAULT 'ALL',
    credit_account_id UUID,

    -- Las dos cifras que no cuadran, y su diferencia. Guardar las tres —y no sólo el delta— es lo
    -- que permite saber CUÁL de los dos lados se movió sin volver a calcular nada.
    left_amount    NUMERIC(19,4),
    right_amount   NUMERIC(19,4),
    delta          NUMERIC(19,4),

    status         VARCHAR(12)   NOT NULL DEFAULT 'OPEN',
    detail         JSONB,
    detected_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,
    resolved_by    VARCHAR(80),
    resolution_note VARCHAR(500),

    CONSTRAINT pk_reconciliation_findings PRIMARY KEY (finding_id),
    CONSTRAINT chk_finding_check CHECK (check_type IN (
        'PORTFOLIO_VS_LEDGER',      -- el sello de cartera contra la balanza del mayor
        'LEDGER_VS_BANK',           -- la cuenta 1101 contra el estado de cuenta
        'PORTFOLIO_VS_BANK',        -- lo cobrado en cartera contra lo abonado en banco
        'CAUSE_WITHOUT_EFFECT',     -- un cargo/pago que nunca movió el saldo
        'BALANCE_DRIFT',            -- la proyección se separó del valor autoritativo
        'UNSEALED_PREREQUISITE')),  -- se intentó cerrar sin que sellara la fase previa
    CONSTRAINT chk_finding_status CHECK (status IN ('OPEN','REPAIRED','ACCEPTED','ESCALATED'))
);

-- La pregunta que bloquea el sello: ¿queda algo abierto de esta fecha?
CREATE INDEX idx_findings_open ON closing.reconciliation_findings (business_date, check_type)
    WHERE status = 'OPEN';
CREATE INDEX idx_findings_account ON closing.reconciliation_findings (credit_account_id)
    WHERE credit_account_id IS NOT NULL;
