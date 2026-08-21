-- D2 Scoring — Círculo de Crédito report tables (full raw data + detail)

-- ─── Reporte principal ──────────────────────────────────────────────────────

CREATE TABLE scoring.circulo_reports (
    report_id                   UUID        NOT NULL,
    prefetch_id                 UUID        NOT NULL,
    prospect_id                 UUID        NOT NULL,
    status                      VARCHAR(20) NOT NULL,
    folio_consulta              VARCHAR(50),
    folio_otorgante             VARCHAR(50),
    clave_otorgante             VARCHAR(20),
    declaraciones_consumidor    VARCHAR(100),
    queried_at                  TIMESTAMPTZ NOT NULL,
    error_code                  VARCHAR(20),
    error_message               VARCHAR(500),

    -- Persona snapshot
    persona_nombres             VARCHAR(100),
    persona_apellido_paterno    VARCHAR(100),
    persona_apellido_materno    VARCHAR(100),
    persona_fecha_nacimiento    DATE,
    persona_rfc                 VARCHAR(13),
    persona_curp                VARCHAR(18),
    persona_nss                 VARCHAR(11),
    persona_sexo                VARCHAR(1),
    persona_estado_civil        VARCHAR(1),
    persona_nacionalidad        VARCHAR(2),
    persona_num_dependientes    INTEGER,

    -- FICO Score principal
    fico_score_valor            INTEGER,
    fico_score_razones          VARCHAR(500),

    CONSTRAINT pk_circulo_reports PRIMARY KEY (report_id),
    CONSTRAINT fk_circulo_report_prefetch FOREIGN KEY (prefetch_id)
        REFERENCES scoring.bureau_prefetches(prefetch_id),
    CONSTRAINT ck_circulo_report_status CHECK (status IN ('PENDING','SUCCESS','NOT_FOUND','NO_HIT','ERROR'))
);

CREATE INDEX idx_circulo_reports_prefetch_id  ON scoring.circulo_reports(prefetch_id);
CREATE INDEX idx_circulo_reports_prospect_id  ON scoring.circulo_reports(prospect_id);
CREATE INDEX idx_circulo_reports_queried_at   ON scoring.circulo_reports(queried_at DESC);

-- ─── Créditos (historial crediticio) ────────────────────────────────────────

CREATE TABLE scoring.circulo_credits (
    credit_id                   UUID        NOT NULL,
    report_id                   UUID        NOT NULL,
    clave_otorgante             VARCHAR(10),
    nombre_otorgante            VARCHAR(100),
    cuenta_actual               VARCHAR(25),
    tipo_responsabilidad        VARCHAR(1),
    tipo_cuenta                 VARCHAR(1),
    tipo_credito                VARCHAR(2),
    moneda                      VARCHAR(2),
    valor_activo_valuacion      NUMERIC(15,2),
    numero_pagos                INTEGER,
    frecuencia_pagos            VARCHAR(1),
    monto_pagar                 NUMERIC(15,2),
    fecha_apertura              DATE,
    fecha_ultimo_pago           DATE,
    fecha_ultima_compra         DATE,
    fecha_cierre                DATE,
    fecha_reporte               DATE,
    ultima_fecha_saldo_cero     DATE,
    credito_maximo              NUMERIC(15,2),
    saldo_actual                NUMERIC(15,2),
    limite_credito              NUMERIC(15,2),
    saldo_vencido               NUMERIC(15,2),
    numero_pagos_vencidos       INTEGER,
    pago_actual                 VARCHAR(2),
    historico_pagos             TEXT,
    fecha_reciente_historico    DATE,
    fecha_antigua_historico     DATE,
    clave_prevencion            VARCHAR(2),
    total_pagos_reportados      INTEGER,
    peor_atraso                 NUMERIC(15,2),
    fecha_peor_atraso           DATE,
    saldo_vencido_peor_atraso   NUMERIC(15,2),
    monto_ultimo_pago           NUMERIC(15,2),
    registro_impugnado          INTEGER,
    fecha_actualizacion         DATE,

    CONSTRAINT pk_circulo_credits PRIMARY KEY (credit_id),
    CONSTRAINT fk_circulo_credits_report FOREIGN KEY (report_id)
        REFERENCES scoring.circulo_reports(report_id) ON DELETE CASCADE
);

CREATE INDEX idx_circulo_credits_report_id ON scoring.circulo_credits(report_id);

-- ─── Domicilios ─────────────────────────────────────────────────────────────

CREATE TABLE scoring.circulo_addresses (
    address_id                  UUID        NOT NULL,
    report_id                   UUID        NOT NULL,
    direccion                   VARCHAR(200),
    colonia_poblacion           VARCHAR(100),
    delegacion_municipio        VARCHAR(100),
    ciudad                      VARCHAR(100),
    estado                      VARCHAR(4),
    cp                          VARCHAR(5),
    tipo_domicilio              VARCHAR(1),
    fecha_residencia            DATE,
    fecha_registro              DATE,
    id_domicilio                VARCHAR(20),

    CONSTRAINT pk_circulo_addresses PRIMARY KEY (address_id),
    CONSTRAINT fk_circulo_addresses_report FOREIGN KEY (report_id)
        REFERENCES scoring.circulo_reports(report_id) ON DELETE CASCADE
);

CREATE INDEX idx_circulo_addresses_report_id ON scoring.circulo_addresses(report_id);

-- ─── Empleos ────────────────────────────────────────────────────────────────

CREATE TABLE scoring.circulo_employments (
    employment_id               UUID        NOT NULL,
    report_id                   UUID        NOT NULL,
    nombre_empresa              VARCHAR(100),
    puesto                      VARCHAR(100),
    salario_mensual             NUMERIC(15,2),
    moneda                      VARCHAR(2),
    fecha_contratacion          DATE,
    fecha_ultimo_dia            DATE,
    fecha_verificacion          DATE,
    ciudad                      VARCHAR(100),
    estado                      VARCHAR(4),

    CONSTRAINT pk_circulo_employments PRIMARY KEY (employment_id),
    CONSTRAINT fk_circulo_employments_report FOREIGN KEY (report_id)
        REFERENCES scoring.circulo_reports(report_id) ON DELETE CASCADE
);

-- ─── Consultas previas ──────────────────────────────────────────────────────

CREATE TABLE scoring.circulo_inquiries (
    inquiry_id                  UUID        NOT NULL,
    report_id                   UUID        NOT NULL,
    fecha_consulta              DATE,
    nombre_otorgante            VARCHAR(100),
    tipo_credito                VARCHAR(2),
    moneda                      VARCHAR(2),
    importe_credito             NUMERIC(15,2),
    tipo_responsabilidad        VARCHAR(1),

    CONSTRAINT pk_circulo_inquiries PRIMARY KEY (inquiry_id),
    CONSTRAINT fk_circulo_inquiries_report FOREIGN KEY (report_id)
        REFERENCES scoring.circulo_reports(report_id) ON DELETE CASCADE
);

CREATE INDEX idx_circulo_inquiries_report_id ON scoring.circulo_inquiries(report_id);

-- ─── Scores ─────────────────────────────────────────────────────────────────

CREATE TABLE scoring.circulo_scores (
    score_id                    UUID        NOT NULL,
    report_id                   UUID        NOT NULL,
    nombre_score                VARCHAR(20),
    valor                       INTEGER,
    razones                     VARCHAR(500),

    CONSTRAINT pk_circulo_scores PRIMARY KEY (score_id),
    CONSTRAINT fk_circulo_scores_report FOREIGN KEY (report_id)
        REFERENCES scoring.circulo_reports(report_id) ON DELETE CASCADE
);

CREATE INDEX idx_circulo_scores_report_id ON scoring.circulo_scores(report_id);
