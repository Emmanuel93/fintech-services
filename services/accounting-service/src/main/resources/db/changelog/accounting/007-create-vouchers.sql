--liquibase formatted sql
--changeset accounting:007-create-vouchers author:system
--comment Póliza contable: encabezado con folio por (tipo, sucursal, período) y N renglones que cuadran.

-- Hasta aquí, `journal_entries` era una lista plana de pares débito/crédito y lo único que unía dos
-- líneas del mismo hecho era el prefijo del source_event_id. Eso alcanza para el mayor y no alcanza
-- para nada de lo que pide una contabilidad real: un folio consecutivo que un auditor pueda seguir,
-- un estatus, y sobre todo un hecho con MÁS DE DOS renglones — un pago que liquida capital e
-- intereses a la vez no cabe en un par.
CREATE TABLE accounting.vouchers (
    voucher_id        UUID          NOT NULL DEFAULT gen_random_uuid(),
    voucher_type      VARCHAR(8)    NOT NULL,   -- DIARIO | INGRESO | EGRESO
    -- La sucursal que colocó el crédito, sellada al activarlo. NULL = anterior al sellado; se cuenta
    -- aparte como «Sin sucursal» y no se reparte.
    org_unit_code     VARCHAR(40),
    period            VARCHAR(6)    NOT NULL,   -- YYYYMM
    folio             BIGINT        NOT NULL,   -- consecutivo por (tipo, sucursal, período)
    voucher_date      TIMESTAMPTZ   NOT NULL,   -- la fecha del HECHO, no la del posteo
    concept           VARCHAR(300)  NOT NULL,
    source_event_id   VARCHAR(120)  NOT NULL,
    trigger_event     VARCHAR(60)   NOT NULL,
    credit_account_id UUID,
    obligor_party_id  UUID,
    total_debit       NUMERIC(19,4) NOT NULL,
    total_credit      NUMERIC(19,4) NOT NULL,
    status            VARCHAR(12)   NOT NULL DEFAULT 'POSTED',   -- POSTED | CANCELLED
    -- El hecho pertenecía a un período ya cerrado y se asentó en el primero abierto.
    is_late_posting   BOOLEAN       NOT NULL DEFAULT FALSE,
    original_period   VARCHAR(6),
    reversal_ref      UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_vouchers PRIMARY KEY (voucher_id),
    -- El folio de una sucursal no tiene huecos causados por el movimiento de otra.
    CONSTRAINT uq_vouchers_folio  UNIQUE (voucher_type, org_unit_code, period, folio),
    -- Idempotencia: el mismo hecho reprocesado no genera una segunda póliza.
    CONSTRAINT uq_vouchers_source UNIQUE (source_event_id),
    -- GL-01 a nivel póliza, en la BASE y no sólo en una prueba: una póliza descuadrada es el único
    -- error de este servicio que no se corrige después sin tocar estados financieros publicados.
    CONSTRAINT chk_vouchers_balanced CHECK (total_debit = total_credit),
    CONSTRAINT chk_vouchers_type   CHECK (voucher_type IN ('DIARIO','INGRESO','EGRESO')),
    CONSTRAINT chk_vouchers_status CHECK (status IN ('POSTED','CANCELLED')),
    CONSTRAINT fk_vouchers_reversal FOREIGN KEY (reversal_ref) REFERENCES accounting.vouchers (voucher_id)
);

CREATE INDEX idx_vouchers_period_unit ON accounting.vouchers (period, org_unit_code);
CREATE INDEX idx_vouchers_account     ON accounting.vouchers (credit_account_id, voucher_date DESC);
CREATE INDEX idx_vouchers_party       ON accounting.vouchers (obligor_party_id, voucher_date DESC);
CREATE INDEX idx_vouchers_date        ON accounting.vouchers (voucher_date DESC);

-- El asiento cuelga de la póliza. **Se conserva el modelo de par** (una fila = un cargo y su abono)
-- en vez de partirlo en renglones de un solo lado: un pago que liquida capital e intereses son dos
-- pares balanceados —caja/intereses y caja/capital— agrupados por la misma póliza, y eso ya cabe
-- aquí. Partir la fila obligaría a soltar los NOT NULL de las dos cuentas, a reescribir el evento
-- `journal-entry-created` que otros consumen, y a migrar todo lo histórico. Los renglones de un solo
-- lado que muestra la consola se derivan agregando por cuenta dentro de la póliza.
--
-- `org_unit_code` se copia también aquí: la balanza por sucursal agrega asientos, y evitar el JOIN
-- contra el encabezado en cada agregación del mayor es lo que esta denormalización compra. El
-- encabezado es inmutable, así que no hay riesgo de divergencia.
ALTER TABLE accounting.journal_entries
    ADD COLUMN voucher_id    UUID REFERENCES accounting.vouchers (voucher_id),
    ADD COLUMN org_unit_code VARCHAR(40),
    ADD COLUMN line_no       SMALLINT;

CREATE INDEX idx_journal_entries_voucher   ON accounting.journal_entries (voucher_id);
CREATE INDEX idx_journal_entries_unit_per  ON accounting.journal_entries (period, org_unit_code);

-- ── Migración de lo ya asentado ────────────────────────────────────────────
-- Una póliza por source_event_id distinto. Sin sucursal (no existía el dato) y con folio asignado
-- por orden de posteo dentro de su período. Inventarles una sucursal sería peor que no tenerla:
-- un número que cuadra y miente es más difícil de detectar que un hueco declarado.
INSERT INTO accounting.vouchers (
    voucher_id, voucher_type, org_unit_code, period, folio, voucher_date, concept,
    source_event_id, trigger_event, credit_account_id, obligor_party_id,
    total_debit, total_credit, status, created_at)
SELECT
    gen_random_uuid(),
    CASE
        WHEN g.trigger_event IN ('PAYMENT_APPLIED','RECOVERY_PAYMENT')            THEN 'INGRESO'
        WHEN g.trigger_event IN ('PAYMENT_RETURNED','WALLET_WITHDRAWAL')          THEN 'EGRESO'
        WHEN g.trigger_event LIKE 'DISPOSITION%'                                  THEN 'EGRESO'
        ELSE 'DIARIO'
    END,
    NULL,
    g.period,
    ROW_NUMBER() OVER (PARTITION BY g.period, CASE
        WHEN g.trigger_event IN ('PAYMENT_APPLIED','RECOVERY_PAYMENT')            THEN 'INGRESO'
        WHEN g.trigger_event IN ('PAYMENT_RETURNED','WALLET_WITHDRAWAL')          THEN 'EGRESO'
        WHEN g.trigger_event LIKE 'DISPOSITION%'                                  THEN 'EGRESO'
        ELSE 'DIARIO'
    END ORDER BY g.first_posting, g.source_event_id),
    g.first_posting,
    COALESCE(g.concept, g.trigger_event),
    g.source_event_id,
    g.trigger_event,
    g.credit_account_id,
    g.obligor_party_id,
    g.total,
    g.total,
    'POSTED',
    g.first_posting
FROM (
    SELECT source_event_id,
           MIN(trigger_event)     AS trigger_event,
           MIN(description)       AS concept,
           -- Postgres no tiene min(uuid): se agrega por texto y se vuelve a tipar. Todas las filas
           -- de un mismo source_event_id comparten crédito y party, así que cualquiera sirve; MIN
           -- se usa sólo porque hace falta *una* función de agregación, no por el orden.
           MIN(credit_account_id::text)::uuid AS credit_account_id,
           MIN(obligor_party_id::text)::uuid  AS obligor_party_id,
           MIN(period)            AS period,
           MIN(posting_date)      AS first_posting,
           SUM(amount)            AS total
    FROM accounting.journal_entries
    GROUP BY source_event_id
) g;

UPDATE accounting.journal_entries e
   SET voucher_id = v.voucher_id
  FROM accounting.vouchers v
 WHERE v.source_event_id = e.source_event_id;

-- La sucursal la trae el evento de cartera, pero sólo la traen los eventos nuevos. Se recuerda en el
-- shadow —que ya tiene una fila por cuenta de crédito— para que, una vez aprendida, la lleven todas
-- las pólizas siguientes de ese préstamo aunque un payload la omita. Sin esto, la atribución por
-- sucursal dependería de que ningún productor se saltara el campo nunca.
ALTER TABLE accounting.account_balance_shadows ADD COLUMN org_unit_code VARCHAR(40);

-- ── Períodos ───────────────────────────────────────────────────────────────
-- `accounting_periods` existía desde 003 y NINGUNA línea la consultaba: se podía asentar en un
-- período cerrado sin resistencia. A partir de aquí el posteo la mira, así que tiene que estar
-- poblada con los períodos que ya tienen movimiento — si no, el primer asiento del día siguiente
-- se consideraría extemporáneo de un período que "no existe".
INSERT INTO accounting.accounting_periods (period, status)
SELECT DISTINCT period, 'OPEN' FROM accounting.journal_entries
    ON CONFLICT (period) DO NOTHING;
