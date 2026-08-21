--liquibase formatted sql
--changeset beneficiary:005-align-with-app-contract author:system
-- Alinea el agregado con `KREDIUS_COLOCACION_API.md`, el contrato que la app ya consume.

-- El pago quincenal que ve la beneficiaria. Se congela al crear la colocación: la app lo estima
-- mientras se arrastra el slider, pero la cifra del contrato es ésta.
ALTER TABLE beneficiary.placements
    ADD COLUMN fortnightly_payment NUMERIC(15,2);

-- La ventana de la liga. Vive aquí y no en el token porque es de la colocación, no del secreto.
ALTER TABLE beneficiary.placements
    ADD COLUMN invite_expires_at TIMESTAMPTZ;

-- Backfill antes de imponer NOT NULL. En una tabla que todavía no ve producción no hay filas que
-- llenar, pero dejarlo escrito hace el changeset reejecutable sobre cualquier entorno de prueba
-- que sí las tenga.
UPDATE beneficiary.placements SET fortnightly_payment = 0.01 WHERE fortnightly_payment IS NULL;
UPDATE beneficiary.placements SET invite_expires_at = created_at + INTERVAL '7 days'
 WHERE invite_expires_at IS NULL;

ALTER TABLE beneficiary.placements ALTER COLUMN fortnightly_payment SET NOT NULL;
ALTER TABLE beneficiary.placements ALTER COLUMN invite_expires_at   SET NOT NULL;

ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_placement_fortnightly_payment CHECK (fortnightly_payment > 0);

-- PAID_OFF: la colocación liquidada. DISBURSED deja de ser terminal — entre el depósito y el
-- último pago hay meses de vida que el agregado tiene que poder representar.
ALTER TABLE beneficiary.placements DROP CONSTRAINT chk_placement_status;
ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_placement_status CHECK (status IN (
        'INVITED', 'KYC_IN_PROGRESS', 'KYC_COMPLETED', 'BUREAU_READY',
        'APPROVED', 'DISBURSING', 'DISBURSED', 'PAID_OFF',
        'REJECTED', 'EXPIRED', 'CANCELLED', 'FAILED'));

-- Los dos CHECK que enumeran estados tienen que conocer PAID_OFF, o una colocación liquidada
-- dejaría de cumplir invariantes que sí cumple.
ALTER TABLE beneficiary.placements DROP CONSTRAINT chk_placement_disposition_required;
ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_placement_disposition_required CHECK (
        status NOT IN ('DISBURSING', 'DISBURSED', 'PAID_OFF') OR disposition_id IS NOT NULL);

-- Una sola liga viva por (distribuidor, celular). Se bloquea por distribuidor y no globalmente:
-- dos distribuidores distintos pueden colocarle a la misma persona, y eso es negocio legítimo.
-- Sólo cubre la fase pre-decisión: después, «enviarle otro préstamo» es una función del diseño.
CREATE UNIQUE INDEX uq_placements_live_invite_per_phone
    ON beneficiary.placements (distributor_party_id, beneficiary_phone)
    WHERE status IN ('INVITED', 'KYC_IN_PROGRESS', 'KYC_COMPLETED', 'BUREAU_READY');

-- El barrido de vencimiento pregunta por invite_expires_at, no por created_at: reenviar reinicia
-- la ventana, así que la fecha de alta dejó de decir cuándo vence.
DROP INDEX IF EXISTS beneficiary.idx_placements_live_invites;
CREATE INDEX idx_placements_live_invites
    ON beneficiary.placements (invite_expires_at)
    WHERE status IN ('INVITED', 'KYC_IN_PROGRESS');
