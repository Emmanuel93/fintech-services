--liquibase formatted sql
--changeset credit-portfolio:023-aml-en-la-disposicion author:system
--comment Qué se sabía de listas en el momento exacto en que salió el dinero.

-- ─────────────────────────────────────────────────────────────────────────────
-- Esto existe porque el negocio ASUMIÓ un riesgo, y un riesgo asumido necesita prueba.
--
-- Decisión (2026-09-14): al activarse una lista nueva, los veredictos vigentes **siguen valiendo**
-- mientras corre el re-cruce. Nada se detiene. La ventana entre la activación y el fin del
-- re-cruce se acepta, y se mitiga corriendo el re-cruce en ventana de mantenimiento.
--
-- La consecuencia es concreta: una disposición puede salir a las 10:06 con veredicto CLEARED, y a
-- las 10:30 el re-cruce marcar a esa persona. Mirando después, se vería a alguien señalado y un
-- desembolso que pasó — y **no habría forma de distinguir «no lo sabíamos» de «lo dejamos pasar»**.
--
-- Estas columnas son esa distinción. Guardan lo que se sabía EN ESE INSTANTE:
-- qué decía el veredicto y contra qué versiones de lista se había calculado.
--
--   sin esto → «esta persona está en listas y le desembolsaron»
--   con esto → «a las 10:06 su veredicto era CLEARED contra la lista del día 3, publicada
--               entonces; la que la señala se activó a las 10:05 y el re-cruce terminó a las 10:30»
--
-- Lo primero es un hallazgo de auditoría. Lo segundo es un control funcionando dentro de su
-- ventana conocida.
-- ─────────────────────────────────────────────────────────────────────────────

-- Primero, la proyección necesita cargar consigo contra qué listas se calculó el veredicto y
-- cuándo: es lo que se copia en cada disposición. Va aquí y NO editando el changeset 022, que ya se
-- aplicó — reescribir un changeset aplicado rompe a Liquibase por checksum en toda base que lo
-- tenga, y el repo ya lo tiene escrito en `scoring/011`.
ALTER TABLE credit_portfolio.party_verifications
    ADD COLUMN screened_list_versions VARCHAR(1000),
    ADD COLUMN verdict_at             TIMESTAMPTZ;

ALTER TABLE credit_portfolio.dispositions
    ADD COLUMN aml_screening_status VARCHAR(20),
    ADD COLUMN aml_list_versions    VARCHAR(1000),
    ADD COLUMN aml_verdict_at       TIMESTAMPTZ,
    ADD COLUMN aml_checked_at       TIMESTAMPTZ;

COMMENT ON COLUMN credit_portfolio.dispositions.aml_screening_status IS
    'El veredicto de listas que gobernó ESTA disposición. No se recalcula ni se actualiza después: '
    'es una foto, y su valor está justamente en que no cambie cuando cambie el veredicto vigente.';

COMMENT ON COLUMN credit_portfolio.dispositions.aml_list_versions IS
    'Contra qué versiones de lista se había calculado ese veredicto. Es lo que permite decir «la '
    'lista que la señala se activó después» en vez de tener que demostrarlo reconstruyendo fechas.';

COMMENT ON COLUMN credit_portfolio.dispositions.aml_verdict_at IS
    'Cuándo se calculó el veredicto. La distancia con aml_checked_at es la antigüedad del dato que '
    'se usó — y es la medida real de la ventana asumida, disposición por disposición.';

COMMENT ON COLUMN credit_portfolio.dispositions.aml_checked_at IS
    'Cuándo se comprobó, al procesar la disposición.';

-- Las disposiciones que salieron con un veredicto viejo. Es la consulta que hay que poder hacer
-- después de un re-cruce: «¿a quién le desembolsamos con información que ya cambió?».
CREATE INDEX idx_dispositions_aml_antiguedad
    ON credit_portfolio.dispositions (aml_verdict_at)
    WHERE aml_verdict_at IS NOT NULL;
