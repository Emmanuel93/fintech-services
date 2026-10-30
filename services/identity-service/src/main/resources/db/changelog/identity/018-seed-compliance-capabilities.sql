-- liquibase formatted sql

-- changeset identity:018 author:fintech
-- La mesa de cumplimiento: ver la cola, dictaminar identidad y adjudicar listas.
--
-- Hasta ahora esa mesa no existía en el backoffice: el dictamen de identidad y la adjudicación de
-- listas sólo se podían hacer por la red interna de Docker, que es lo que hacían los scripts de
-- siembra firmando como `siembra-demo`. Sin proveedor de KYC, TODA alta queda en identidad
-- IN_REVIEW y bloqueada en las compuertas de originación y disposición, así que sin esta mesa nadie
-- con nombre y apellido podía desbloquear a una persona.
--
-- Quién las recibe sigue a quién ya hace el trabajo más parecido hoy —la mesa de KYC de
-- beneficiarias, que es OPS_SUPERVISOR (017)— en vez de inventar un rol de cumplimiento que nadie
-- tiene asignado. Cuando exista el rol, se le otorga a él y se retira de aquí.
INSERT INTO identity.role_capabilities (role_code, capability, granted_by) VALUES
    ('ADMIN',          'compliance.view',            'seed'),
    ('OPS_SUPERVISOR', 'compliance.view',            'seed'),
    -- El auditor lee todo y no decide nada: ver la cola es justo lo que revisa.
    ('AUDITOR',        'compliance.view',            'seed'),
    ('ADMIN',          'compliance.review-identity', 'seed'),
    ('OPS_SUPERVISOR', 'compliance.review-identity', 'seed'),
    -- Adjudicar listas es la decisión regulatoria más delicada de la mesa: confirmar una
    -- coincidencia abre un reporte a la UIF. Se queda en los mismos dos roles y en ninguno más.
    ('ADMIN',          'compliance.adjudicate',      'seed'),
    ('OPS_SUPERVISOR', 'compliance.adjudicate',      'seed')
ON CONFLICT (role_code, capability) DO NOTHING;
