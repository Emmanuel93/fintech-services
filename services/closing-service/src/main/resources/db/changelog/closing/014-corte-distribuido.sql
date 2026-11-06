--liquibase formatted sql
--changeset closing:014-corte-distribuido
--comment El corte se reparte por cuenta: cada unidad sabe de quién es y cuándo se despachó.

-- Una unidad de corte es una cuenta de un cliente o de una distribuidora. Lo que se agrega aquí es
-- lo que el reparto necesita sin volver a leer el perfil: de quién es (obligor), cómo se comporta
-- el producto (REVOLVING / INSTALLMENT) y qué tipo de titular es.
ALTER TABLE closing.close_units
    ADD COLUMN obligor_party_id UUID,
    ADD COLUMN product_behavior VARCHAR(20),
    ADD COLUMN holder_kind      VARCHAR(20),
    -- Cuándo se publicó la unidad al tópico de trabajo. Nula = nunca salió; vieja y aún PENDING =
    -- el mensaje se perdió o el consumidor murió antes de tomarla. El monitor la vuelve a mandar.
    ADD COLUMN dispatched_at    TIMESTAMPTZ;

ALTER TABLE closing.close_units
    ADD CONSTRAINT chk_unit_holder_kind CHECK (holder_kind IS NULL OR holder_kind IN ('CUSTOMER','DISTRIBUTOR'));

-- El monitor busca lo PENDING sin despachar o con despacho viejo, por corrida.
CREATE INDEX idx_units_dispatch ON closing.close_units (run_id, dispatched_at NULLS FIRST)
    WHERE status = 'PENDING';

-- Cuántas cuentas entraron al corte, por producto y por tipo de titular. Se congela al planificar:
-- es la cifra contra la que se mide el avance del corte y de los estados de cuenta.
ALTER TABLE closing.close_runs
    ADD COLUMN breakdown    JSONB,
    ADD COLUMN completed_at TIMESTAMPTZ;

-- Las corridas abiertas que el monitor revisa.
CREATE INDEX idx_runs_open ON closing.close_runs (phase, status) WHERE status IN ('PLANNED','RUNNING');

-- Candado de crones (ShedLock): el job que planifica y el monitor corren en UNA réplica.
-- Las unidades, en cambio, las trabajan todas.
CREATE TABLE IF NOT EXISTS closing.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
