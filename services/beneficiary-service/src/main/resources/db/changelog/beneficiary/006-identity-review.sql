--liquibase formatted sql
--changeset beneficiary:006-identity-review author:system
--comment Veredicto de identidad con autor: deja de derivarse del avance de la colocación.

-- ─────────────────────────────────────────────────────────────────────────────
-- `IdentityStatus` se derivaba de `PlacementStatus`: en cuanto la beneficiaria terminaba su KYC, la
-- identidad figuraba como VERIFIED. Eso confundía dos hechos distintos —«entregó sus documentos» y
-- «alguien comprobó que es ella»— y, sobre todo, **no dejaba dónde poner el juicio de una persona**.
--
-- Mientras no haya contrato con proveedor de KYC, toda revisión es manual: un analista mira la
-- evidencia y firma. Cuando lo haya, el proveedor resolverá lo que caiga dentro de sus umbrales y el
-- analista sólo verá las excepciones — pero el lugar donde se escribe el veredicto es el mismo, y
-- `identity_verification_source` distingue quién lo emitió.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE beneficiary.placements
    ADD COLUMN identity_decision            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN identity_verification_source VARCHAR(25),
    ADD COLUMN identity_decided_by          VARCHAR(120),
    ADD COLUMN identity_decided_at          TIMESTAMPTZ,
    ADD COLUMN identity_rejection_reason    VARCHAR(500),
    -- Por qué está donde está: «PROVEEDOR_NO_DISPONIBLE», «UMBRAL_NO_ALCANZADO: facial 0.82 < 0.90».
    -- Es lo que el analista lee antes de abrir el expediente.
    ADD COLUMN identity_review_notes         VARCHAR(1000);

ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_identity_decision
        CHECK (identity_decision IN ('PENDING', 'VERIFIED', 'REJECTED'));

ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_identity_source
        CHECK (identity_verification_source IS NULL
            OR identity_verification_source IN ('MANUAL', 'PROVIDER', 'PROVIDER_ESCALATED'));

-- Un veredicto resuelto tiene autor, fecha y origen. Sin esto la base admitiría una identidad
-- «comprobada» que nadie firmó, y es la firma lo que la vuelve evidencia.
ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_identity_authored
        CHECK (identity_decision = 'PENDING'
            OR (identity_decided_by IS NOT NULL AND identity_decided_at IS NOT NULL
                AND identity_verification_source IS NOT NULL));

ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_identity_rejection_reason
        CHECK (identity_decision <> 'REJECTED'
            OR (identity_rejection_reason IS NOT NULL AND identity_rejection_reason <> ''));

-- Nada se aprueba sin identidad comprobada. La regla vive también en el dominio; aquí es la red
-- que atrapa cualquier camino que la esquive —una carga masiva, un script de soporte—.
ALTER TABLE beneficiary.placements
    ADD CONSTRAINT chk_approved_requires_identity
        CHECK (status NOT IN ('APPROVED', 'DISBURSING', 'DISBURSED', 'PAID_OFF')
            OR identity_decision = 'VERIFIED');

-- La bandeja de la mesa: lo que espera dictamen, con expediente ya formado.
CREATE INDEX idx_placements_identity_pending
    ON beneficiary.placements (updated_at)
    WHERE identity_decision = 'PENDING' AND beneficiary_party_id IS NOT NULL;
