--liquibase formatted sql
--changeset closing:011-drop-bank
--comment BK-04 · las 5 tablas `bank_*` se mudan a `banking`. Aquí sólo queda su partida de defunción.

-- El changeset `011-create-bank` que las creaba fue **retirado**, no editado: en una base nueva ya
-- no se crean, y en una que las tenga aplicadas este DROP las retira. La fila huérfana que queda en
-- `databasechangelog` es inofensiva — Liquibase no exige que todo changeset registrado siga
-- existiendo en el changelog.
--
-- Se puede tirar sin ceremonia porque **no había código que las tocara**: cero clases Java, cero
-- repositorios, cero consultas. El único lector era la prueba que verifica el esquema, y se mudó
-- con las tablas (AN-12).
--
-- El orden importa: las hijas antes que la madre. `CASCADE` cubre lo que el orden no.
DROP TABLE IF EXISTS closing.bank_close_seals    CASCADE;
DROP TABLE IF EXISTS closing.suspense_entries    CASCADE;
DROP TABLE IF EXISTS closing.bank_matches        CASCADE;
DROP TABLE IF EXISTS closing.bank_statement_lines CASCADE;
DROP TABLE IF EXISTS closing.bank_accounts       CASCADE;
