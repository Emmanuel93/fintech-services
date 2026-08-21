--liquibase formatted sql

--changeset audit-service:009-add-full-identity
-- Identidad completa de quien actúa y de aquél sobre quien se actúa.
--
-- La 008 congeló el correo y el nombre del actor, y con eso la bitácora ya decía algo legible.
-- No basta para la trazabilidad exigida: identificar plenamente a una persona física incluye su
-- CURP, y tratándose de un cliente o un distribuidor, también su teléfono. Lo que hoy identifica
-- a la mayoría de los actores es un UUID.
--
-- Y falta la otra mitad de la pregunta. «Quién actuó» y «sobre quién» son cosas distintas: la
-- primera acusa, la segunda es la que contesta «quién abrió el expediente de esta persona», que es
-- justamente lo que una revisión pregunta.
--
-- El sujeto se apunta en la columna party_id que ya existe —no se añade otra: tener party_id y
-- subject_party_id conviviendo obligaría a cada consulta a saber cuál mirar según la categoría de
-- la fila, que es la clase de trampa que hace inservible una bitácora—. Venía vacía en el 91% de
-- las entradas porque el camino de acceso no la llenaba; ahora sí. Lo que se añade aquí es su
-- identidad resuelta, para que el sujeto se lea sin ir a buscarlo a otro servicio.
--
-- SNAPSHOT — mismo criterio que la 008 y por la misma razón: los valores se congelan al escribir.
-- Si mañana el cliente cambia de teléfono, la entrada conserva el de entonces. Una bitácora que
-- siguiera al dato vivo reescribiría el pasado cada vez que alguien actualiza su perfil, y dejaría
-- de servir como prueba de lo que se sabía en el momento del acceso.
--
-- Todas nullable: la bitácora es inmutable y no hay backfill posible. Lo existente se queda como
-- está; el alcance es de aquí en adelante.
ALTER TABLE audit.audit_entries
    -- Quién actuó
    ADD COLUMN actor_curp    VARCHAR(18),
    ADD COLUMN actor_phone   VARCHAR(32),
    -- Sobre quién se actuó (el id va en party_id, que ya existe)
    ADD COLUMN subject_name  VARCHAR(255),
    ADD COLUMN subject_curp  VARCHAR(18),
    ADD COLUMN subject_email VARCHAR(255),
    ADD COLUMN subject_phone VARCHAR(32);

-- Las dos preguntas que la bitácora tiene que contestar rápido: «qué hizo esta persona» y
-- «quién tocó a esta persona». Parciales porque el grueso de las entradas son hechos de dominio,
-- que no traen ninguna de las dos.
CREATE INDEX audit_entries_actor_curp_idx ON audit.audit_entries (actor_curp)
    WHERE actor_curp IS NOT NULL;
CREATE INDEX audit_entries_subject_curp_idx ON audit.audit_entries (subject_curp)
    WHERE subject_curp IS NOT NULL;

-- «Todo lo que le pasó a este cliente, lo más reciente primero» — la consulta con la que empieza
-- cualquier revisión. El índice de party_id de la 002 no ordena, así que resolverla obligaba a
-- ordenar el resultado entero.
CREATE INDEX audit_entries_party_occurred_idx ON audit.audit_entries (party_id, occurred_at DESC)
    WHERE party_id IS NOT NULL;
