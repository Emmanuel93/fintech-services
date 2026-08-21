--liquibase formatted sql
--changeset collections:011-queue-indexes author:system
-- Índices de las bandejas transversales (promesas y contactos cruzando casos).
--
-- `payment_promises` sólo tenía índice por `case_id`, que sirve al sub-recurso de un caso pero no
-- a la bandeja: «promesas vigentes que vencen hoy» filtra por estado y fecha prometida sobre toda
-- la tabla. Sin esto, la consulta que sustituye al N+1 sería un scan completo — se habría cambiado
-- una llamada por fila por una lectura de tabla entera, que no es un arreglo.
--
-- `contact_attempts` ya trae `(case_id, attempted_at)` desde la 004, que es justo lo que necesitan
-- las dos subconsultas correlacionadas (intentos de hoy y último resultado). No se duplica.

CREATE INDEX idx_payment_promises_status_due
    ON collections.payment_promises (status, promised_date);

-- El filtro por gestor y tramo entra por el JOIN al caso; el caso ya está indexado por su PK, pero
-- la bandeja filtrada por gestor recorre casos, no promesas.
CREATE INDEX idx_collection_cases_agent_bucket
    ON collections.collection_cases (assigned_agent_id, current_bucket);
