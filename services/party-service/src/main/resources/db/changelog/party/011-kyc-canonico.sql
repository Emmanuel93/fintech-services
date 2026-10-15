--liquibase formatted sql

--changeset party:011-kyc-canonico
--comment El KYC deja de ser un registro mudo: dice cómo se resolvió, quién y con qué evidencia.

-- **Un solo proceso de KYC para todo el que quede registrado.** Hoy hay dos modelos que hacen lo
-- mismo, y el más completo es el que cubre a menos gente: `beneficiary.placements` guarda modo y
-- origen del veredicto; `party.kyc_verifications` —que cubre a TODO el mundo— no guarda ninguno de
-- los dos. Toda beneficiaria es o llega a ser un `party` (`placements.beneficiary_party_id`), así
-- que el modelo canónico es éste y la colocación lo referencia en vez de llevar su copia.
--
-- Lo que se añade, y por qué cada columna:
--
--   verification_mode    ¿se intentó resolver solo, o fue a una persona desde el principio? La
--                        EXIGENCIA la decide el producto, no una bandera del servicio: una línea de
--                        distribuidor y una tarjeta de uso propio no piden lo mismo. `party` no
--                        conoce productos — guarda lo que ocurrió, no lo que la política dice hoy.
--
--   verification_source  quién emitió el veredicto. Se guarda y no se deduce de la configuración
--                        vigente: un expediente de hace seis meses tiene que poder decir quién lo
--                        revisó ENTONCES, y deducirlo reescribiría la historia cada vez que alguien
--                        toca una variable de entorno.
--
--   provider             CUÁL proveedor, no sólo que hubo uno. Con más de uno en el horizonte,
--                        «PROVIDER» deja de ser una respuesta.
--
--   provider_reference   el identificador de la sesión del proveedor — el `interviewId` de Incode.
--                        Sin esto, el día que el proveedor pregunte «¿qué entrevista fue?» no hay
--                        respuesta, y una verificación que no se puede reabrir no se puede defender.
--
--   provider_metadata    JSONB y no columnas por proveedor: Incode devuelve `liveness` y
--                        `facialMatch`, otro devolverá otra cosa. Modelar la unión de todos los
--                        proveedores en columnas es acoplarse a todos a la vez.
ALTER TABLE party.kyc_verifications
    ADD COLUMN verification_mode   VARCHAR(20),
    ADD COLUMN verification_source VARCHAR(20),
    ADD COLUMN provider            VARCHAR(30),
    ADD COLUMN provider_reference  VARCHAR(120),
    ADD COLUMN provider_metadata   JSONB;

-- Nulo es «no se sabe», y es la verdad para lo ya registrado: esas filas se crearon cuando el
-- modelo no guardaba nada de esto. Rellenarlas con MANUAL/NONE sería inventar un dato que nadie
-- comprobó — justo lo que estas columnas existen para evitar.
COMMENT ON COLUMN party.kyc_verifications.verification_mode IS
    'AUTOMATIC | MANUAL — cómo se intentó resolver. NULL en filas anteriores a esta migración.';
COMMENT ON COLUMN party.kyc_verifications.verification_source IS
    'MANUAL | PROVIDER | PROVIDER_ESCALATED — quién emitió el veredicto, tal como fue entonces.';
COMMENT ON COLUMN party.kyc_verifications.provider IS
    'INCODE | JUMIO | NONE — cuál proveedor resolvió. NULL si no se sabe.';
COMMENT ON COLUMN party.kyc_verifications.provider_reference IS
    'Identificador de la sesión en el proveedor (interviewId, sessionId). Permite reabrir el caso.';
COMMENT ON COLUMN party.kyc_verifications.provider_metadata IS
    'Lo que el proveedor devolvió y no cabe en columnas: scores por métrica, checks fallidos.';

-- Sólo tiene sentido buscar por referencia cuando la hay, y hoy no la hay en ninguna fila.
CREATE INDEX idx_kyc_provider_reference
    ON party.kyc_verifications (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;

-- Un veredicto de proveedor sin decir cuál es exactamente el hueco que esta migración cierra.
ALTER TABLE party.kyc_verifications
    ADD CONSTRAINT ck_kyc_source_exige_proveedor
        CHECK (verification_source IS NULL
               OR verification_source = 'MANUAL'
               OR provider IS NOT NULL);
