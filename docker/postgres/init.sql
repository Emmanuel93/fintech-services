-- Inicialización de PostgreSQL para fintech-services.
-- Este script se ejecuta una sola vez al crear el contenedor.
-- Liquibase crea los schemas por módulo; este script verifica conectividad y crea sus tablas de control.

SELECT version();

-- Liquibase's tracking tables, created once before any service starts. Every service keeps its changelog history in
-- public: on a virgin platform the services raced to create these tables, and the loser crashed on start-up ("relation
-- databasechangelog already exists": audit and party, 4 Nov 2026) and came back only on its restart. Created here
-- there is nothing to race for: each service takes the lock and appends its own changesets. Same DDL Liquibase uses.
CREATE TABLE IF NOT EXISTS public.databasechangeloglock (
    id          INTEGER      NOT NULL,
    locked      BOOLEAN      NOT NULL,
    lockgranted TIMESTAMP WITHOUT TIME ZONE,
    lockedby    VARCHAR(255),
    CONSTRAINT databasechangeloglock_pkey PRIMARY KEY (id)
);
INSERT INTO public.databasechangeloglock (id, locked) VALUES (1, FALSE) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.databasechangelog (
    id            VARCHAR(255) NOT NULL,
    author        VARCHAR(255) NOT NULL,
    filename      VARCHAR(255) NOT NULL,
    dateexecuted  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    orderexecuted INTEGER      NOT NULL,
    exectype      VARCHAR(10)  NOT NULL,
    md5sum        VARCHAR(35),
    description   VARCHAR(255),
    comments      VARCHAR(255),
    tag           VARCHAR(255),
    liquibase     VARCHAR(20),
    contexts      VARCHAR(255),
    labels        VARCHAR(255),
    deployment_id VARCHAR(10)
);
