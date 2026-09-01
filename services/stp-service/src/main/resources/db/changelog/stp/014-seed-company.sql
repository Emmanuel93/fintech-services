--liquibase formatted sql
--changeset stp-service:014-seed-company
--comment La empresa del conector. Sin ella el pago llega hasta STP y no se puede firmar.

-- **La misma empresa tiene que existir en los dos lados y sólo se sembró uno.** `disbursement`
-- resuelve `9b1d0000-…-0001` con su comodín (changeset 008 de ese servicio) y despacha; el conector
-- recibe la orden y no tiene con qué firmarla:
--
--     CompanyNotFoundException: No hay empresa activa con companyId=9b1d0000-0000-4000-8000-000000000001
--
-- El UUID es el mismo a propósito: es la misma empresa vista desde dos servicios. `disbursement`
-- guarda de quién es el pago; `stp` guarda con qué identidad se firma ante Banxico —empresa STP,
-- institución operante y prefijo de clave de rastreo—. Que sean tablas distintas es correcto; que
-- sólo una estuviera sembrada es lo que dejaba el carril cortado en el último eslabón.
--
-- Se descubrió corriendo el carril de punta a punta: cuatro órdenes DISPATCHED sin clave de rastreo
-- y cuatro mensajes en la DLT de `disbursement.stp-requested`. Ninguna prueba lo miraba, porque
-- ninguna cruzaba de disbursement a stp.
INSERT INTO stp.companies
    (company_id, code, stp_empresa, institucion_operante, tracking_prefix,
     clabe_bank_code, clabe_plaza_code, clabe_client_prefix, status, created_at)
VALUES
    ('9b1d0000-0000-4000-8000-000000000001', 'KREDIUS', 'KREDIUS', 90646, 'KRD',
     '646', '180', '000001', 'ACTIVE', NOW())
ON CONFLICT (company_id) DO NOTHING;
