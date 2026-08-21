--liquibase formatted sql
--changeset wallet:007-create-wallet-movements author:system

-- Ledger unificado de movimientos del wallet para la vista de "Historial" de la app.
-- Un renglón por cada evento que afecta el saldo/deuda visible al cliente:
--   DISPOSITION (CREDIT) — dinero dispuesto que entra al walletBalance (SELF_USE)
--   WITHDRAWAL  (DEBIT)  — retiro SPEI/CoDi que sale del walletBalance
--   PAYMENT     (DEBIT)  — instrucción de pago hacia el crédito
CREATE TABLE wallet.wallet_movements (
    movement_id       UUID          NOT NULL DEFAULT gen_random_uuid(),
    credit_account_id UUID          NOT NULL,
    obligor_party_id  UUID          NOT NULL,
    type              VARCHAR(20)   NOT NULL,
    direction         VARCHAR(6)    NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    description       VARCHAR(160),
    status            VARCHAR(20)   NOT NULL DEFAULT 'COMPLETED',
    reference         VARCHAR(100),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_wallet_movements PRIMARY KEY (movement_id),
    CONSTRAINT chk_wallet_movements_type CHECK (type IN ('DISPOSITION', 'WITHDRAWAL', 'PAYMENT')),
    CONSTRAINT chk_wallet_movements_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT chk_wallet_movements_amount CHECK (amount > 0)
);

CREATE INDEX idx_wallet_movements_account_date
    ON wallet.wallet_movements (credit_account_id, created_at DESC);
