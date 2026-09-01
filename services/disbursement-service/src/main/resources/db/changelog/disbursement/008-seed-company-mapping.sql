--liquibase formatted sql
--changeset disbursement-service:008-seed-company-mapping
--comment El mapeo de empresa por defecto. Sin él, NINGÚN pago se puede crear.

-- **El mismo hueco que las rutas, en otro sitio.** `company_mappings` tampoco la sembró nunca nadie:
-- `grep "INSERT INTO disbursement.company_mappings"` en todo el repo no devuelve nada. Con la tabla
-- vacía, DB-07 rechaza toda orden con `UNRESOLVED_COMPANY` antes de llegar al proveedor.
--
-- Y no se notaba porque el `Noop` de cartera cortocircuitaba el carril antes. La prueba con Spring
-- de los listeners ACL lo destapó en su primera ejecución.
--
-- Comodín `*`: cubre a todo el sistema origen que no tenga mapeo propio. Es lo que evita que dar de
-- alta una sucursal nueva rompa sus pagos en silencio, el día que alguien coloque el primer crédito
-- ahí y nadie recuerde que había que mapearla. Un mapeo específico siempre gana.
INSERT INTO disbursement.company_mappings
    (company_mapping_id, source_system, source_key, company_id, enabled, created_at)
VALUES
    ('7c1d0000-0000-4000-8000-000000000001', 'credit-portfolio', '*',
     '9b1d0000-0000-4000-8000-000000000001', TRUE, NOW()),
    ('7c1d0000-0000-4000-8000-000000000002', 'wallet', '*',
     '9b1d0000-0000-4000-8000-000000000001', TRUE, NOW());
