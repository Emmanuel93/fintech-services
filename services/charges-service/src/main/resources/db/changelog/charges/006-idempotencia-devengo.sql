--liquibase formatted sql

--changeset charges-service:006-idempotencia-devengo
--comment TK-02: la clave natural que impide el cargo duplicado, venga de donde venga.

-- **Por qué esto es lo primero que se arregla.** El devengo diario se protegía sólo con
-- `last_accrual_date`: se lee, se compara y se escribe en pasos separados. Dos réplicas —o dos
-- corridas del mismo job— pasan las dos la comparación y generan dos cargos con `charge_id`
-- distinto. Como la idempotencia de cartera va por `sourceEventId` y el id es distinto, el
-- duplicado la atraviesa entera y llega al mayor.
--
-- El candado distribuido evita el trabajo duplicado; esto evita el DATO duplicado. Son cosas
-- distintas y hacen falta las dos: si el candado falla, aquí rebota.

-- ── Primero: los duplicados que YA existen ───────────────────────────────────
--
-- **Esta restricción no se puede imponer sobre datos que ya la violan, y los datos la violan.** En
-- el ambiente de demostración hay 165 grupos de cargos repetidos —330 cargos de más, $8 250 de
-- interés duplicado en 3 cuentas—. No es un caso hipotético que esta migración previene: es el
-- defecto que ya ocurrió, y la migración lo encontró al intentar crear el índice.
--
-- Se marcan como REVERSED, que es el mecanismo que el dominio ya tiene para «este cargo no cuenta»,
-- y se conserva el más antiguo de cada grupo. **No se borran**: un cargo que se cobró y desaparece
-- deja al mayor con un asiento sin origen, y la bitácora de cargos existe precisamente para poder
-- explicar cada peso.
--
-- ⚠️ **Esto NO corrige el mayor.** Contabilidad ya asentó esos cargos; reversarlos aquí deja los dos
-- lados desalineados hasta que alguien emita las pólizas de reversa. Un changeset de esquema no debe
-- postear en el libro mayor por su cuenta —esa es una decisión contable con fecha y responsable—,
-- así que se deja declarado y no se hace en silencio.
UPDATE charges.charge_records
   SET status = 'REVERSED',
       reversal_reason = 'Duplicado de devengo anterior a la restricción de idempotencia (TK-02)'
 WHERE charge_id IN (
    SELECT charge_id FROM (
        SELECT charge_id,
               ROW_NUMBER() OVER (
                   PARTITION BY credit_account_id, charge_type, accrual_date
                   ORDER BY created_at, charge_id) AS orden
          FROM charges.charge_records
         WHERE charge_type IN ('ORDINARY_INTEREST', 'MORATORIUM_INTEREST')
           AND status <> 'REVERSED'
    ) g WHERE g.orden > 1);

-- El IVA ligado a un cargo reversado se va con él: reversar el interés y dejar su impuesto deja un
-- IVA trasladado sobre un ingreso que ya no existe.
UPDATE charges.charge_records
   SET status = 'REVERSED',
       reversal_reason = 'IVA de un devengo duplicado (TK-02)'
 WHERE charge_type = 'IVA'
   AND status <> 'REVERSED'
   AND linked_charge_id IN (
        SELECT charge_id FROM charges.charge_records
         WHERE status = 'REVERSED'
           AND reversal_reason LIKE 'Duplicado de devengo%');

-- ── Ahora sí: interés ordinario y moratorio, uno por cuenta y día ────────────
--
-- Parcial y no total, porque el IVA NO cumple esta regla: un mismo día puede haber dos —el del
-- interés ordinario y el del moratorio— y una restricción total sobre (cuenta, tipo, fecha)
-- rompería el devengo moratorio de toda cuenta en mora.
--
-- Y el índice excluye lo reversado: si contara los duplicados que acabamos de marcar, no se podría
-- crear — y tampoco tendría sentido, porque un cargo reversado no es un cobro.
CREATE UNIQUE INDEX uq_charge_devengo_diario
    ON charges.charge_records (credit_account_id, charge_type, accrual_date)
    WHERE charge_type IN ('ORDINARY_INTEREST', 'MORATORIUM_INTEREST')
      AND status <> 'REVERSED';

-- ── IVA: uno por cargo padre ────────────────────────────────────────────────
--
-- Su clave natural no es la fecha, es el cargo del que se deriva. Un IVA sin padre no debería
-- existir; si existiera, esta restricción no lo toca.
CREATE UNIQUE INDEX uq_charge_iva_por_padre
    ON charges.charge_records (linked_charge_id)
    WHERE charge_type = 'IVA' AND linked_charge_id IS NOT NULL;

-- ── Comisión de apertura: una por cuenta, para siempre ──────────────────────
--
-- Ya había una guarda en código (`existsByChargeTypeAndCreditAccountId`, CR-05), que es
-- exactamente el patrón leer-comprobar-escribir que no resiste concurrencia.
CREATE UNIQUE INDEX uq_charge_apertura
    ON charges.charge_records (credit_account_id)
    WHERE charge_type = 'OPENING_FEE';

-- ── El devengo moratorio necesita su propio reloj ───────────────────────────
--
-- `accrueMoratoriumForSchedule` nunca llamaba a `markAccruedFor`: se podía repetir el mismo día
-- sin resistencia. Pero no puede compartir `last_accrual_date` con el ordinario — si el job
-- moratorio corriera primero y marcara el día, `needsAccrual` daría falso y **el interés
-- ordinario de ese día no se devengaría**. Se cambiaría un duplicado por una omisión, que es peor.
ALTER TABLE charges.accrual_schedules
    ADD COLUMN last_moratorium_accrual_date DATE;
