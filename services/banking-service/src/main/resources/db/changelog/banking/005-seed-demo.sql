--liquibase formatted sql
--changeset banking:005-seed-demo
--comment Una cuenta propia y una ruta por defecto, para que el carril del dinero EXISTA.

-- **Por qué este seed no es opcional.** Buscando qué migrar de `disbursement.routing_rules` salió
-- algo peor que una tabla mal ubicada: **nadie la sembró nunca**. Ni un changeset, ni un script de
-- `scripts/`. Con la tabla vacía, `RoutingService.findProvider` devolvía siempre vacío, cada orden
-- se aplazaba con `NO_ROUTING_RULE` y a los seis intentos moría en `FAILED`. Lo mismo del lado del
-- conector: sin `ordering_accounts`, registrar una orden lanzaba `OrderingAccountNotFoundException`.
--
-- Que eso no se notara en ningún ambiente es la prueba más limpia de que **el carril del dinero
-- nunca se ejecutó**: el `Noop` de cartera lo cortocircuitaba antes de llegar aquí. Mover la
-- configuración sin sembrarla habría reproducido el mismo hueco con otro nombre.
--
-- Es un seed de **ambiente bajo**: CLABEs de relleno con dígito verificador correcto y una sola
-- ruta genérica. En producción, tesorería da de alta sus cuentas reales por la API y desactiva
-- estas — de ahí que la ruta lleve prioridad 900, para que cualquier regla real le gane.
INSERT INTO banking.bank_accounts
    (bank_account_id, company_id, institution_code, institution_name, clabe, holder_name,
     tax_id, currency, ledger_account, suspense_credit_account, suspense_debit_account,
     provider_client_ref, status)
VALUES
    ('9b1d0000-0000-4000-8000-000000000001', NULL, '646', 'STP',
     '646180000000000012', 'FINTECH SERVICES SA DE CV SOFOM ENR', 'FSE200101AB1', 'MXN',
     '1101', '2109', '1109', 'DEMO-CLIENT-001', 'ACTIVE');

-- Genérica (`company_id` NULL): cubre a todas las empresas, cualquier monto, por SPEI vía STP.
INSERT INTO banking.payout_routes
    (payout_route_id, company_id, rail, provider, bank_account_id, min_amount, max_amount,
     priority, enabled)
VALUES
    ('9b1d0000-0000-4000-8000-0000000000a1', NULL, 'SPEI', 'STP',
     '9b1d0000-0000-4000-8000-000000000001', 0, NULL, 900, TRUE);
