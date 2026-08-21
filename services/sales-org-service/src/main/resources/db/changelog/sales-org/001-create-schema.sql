--liquibase formatted sql
--changeset sales-org:001-create-schema author:system
CREATE SCHEMA IF NOT EXISTS sales_org;
-- LTREE sostiene la jerarquía: el path materializado de cada unidad permite consultar un subárbol
-- completo (el alcance de un empleado) en UNA consulta indexada, sin recursión ni N+1. Se instala en
-- public para que el tipo sea visible desde el search_path por defecto.
CREATE EXTENSION IF NOT EXISTS ltree SCHEMA public;
