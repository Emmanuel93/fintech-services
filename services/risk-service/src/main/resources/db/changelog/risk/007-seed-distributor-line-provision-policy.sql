--liquibase formatted sql
--changeset risk:007-seed-distributor-line-provision-policy author:system
--comment DISTRIBUTOR_LINE se quedó sin política y por eso su cartera nunca provisionaba.

-- El catálogo inicial cubría los cuatro productos B2C y se olvidó de la línea de distribuidora,
-- que se incorporó después. El fallo es silencioso de la peor manera: `reassessAll()` cuenta esas
-- cuentas como `skippedNoPolicy`, contesta 200 y no publica nada. Contabilidad entonces no
-- constituye estimación preventiva sobre ellas, así que un quebranto de una distribuidora golpea
-- resultados entero —no hay reserva que consumir— y la balanza sale «correcta» describiendo una
-- institución que no reservó por su cartera empresarial.
--
-- Las tasas son más altas que las de PERSONAL_LOAN en los tramos profundos a propósito: la línea de
-- distribuidora concentra el riesgo en una contraparte que a su vez presta a sus clientes, así que
-- cuando cae, cae entera. Siguen siendo cifras de calibración pendiente, como el resto del catálogo.

INSERT INTO risk.provision_policies (policy_id, product_type, version, status, created_at) VALUES
    ('55555555-5555-5555-5555-555555555555', 'DISTRIBUTOR_LINE', 1, 'ACTIVE', NOW());

INSERT INTO risk.provision_rate_bands (policy_id, bucket, expected_loss_rate)
SELECT '55555555-5555-5555-5555-555555555555'::uuid, b.bucket, b.rate
FROM (VALUES
    ('CURRENT',   0.01500),
    ('B1_30',     0.05000),
    ('B31_60',    0.20000),
    ('B61_90',    0.40000),
    ('B91_120',   0.70000),
    ('B121_180',  0.90000),
    ('B181_PLUS', 1.00000)
) AS b(bucket, rate);
