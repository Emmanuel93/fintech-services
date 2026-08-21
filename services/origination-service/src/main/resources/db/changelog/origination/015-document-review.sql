--liquibase formatted sql
--changeset origination:015-document-review author:system
--comment Estado de dictamen por documento del expediente.

-- ─────────────────────────────────────────────────────────────────────────────
-- Hasta aquí un documento sólo podía estar entregado o ausente. No había dónde poner el juicio de
-- una persona, así que «revisado y aprobado» y «recibido y nadie lo ha visto» eran el mismo estado
-- —y el analista no tenía forma de saber qué le faltaba por hacer contra qué le faltaba por recibir.
--
-- El dictamen vive en el ARCHIVO y no en la declaración (`prospect_documents`): lo que se revisa es
-- lo entregado, no lo prometido. Además la prueba de vida sólo existe en esta tabla.
--
-- `verification_source` se guarda junto al veredicto y no se deduce de la configuración vigente: la
-- bandera del proveedor cambia con el tiempo y un expediente de hace seis meses tiene que poder
-- decir quién lo revisó ENTONCES.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE origination.prospect_document_files
    ADD COLUMN review_status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING_REVIEW',
    ADD COLUMN verification_source VARCHAR(25),
    ADD COLUMN reviewed_by         VARCHAR(120),
    ADD COLUMN reviewed_at         TIMESTAMPTZ,
    ADD COLUMN rejection_reason    VARCHAR(500);

ALTER TABLE origination.prospect_document_files
    ADD CONSTRAINT chk_document_review_status
        CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'));

ALTER TABLE origination.prospect_document_files
    ADD CONSTRAINT chk_document_verification_source
        CHECK (verification_source IS NULL
            OR verification_source IN ('MANUAL', 'PROVIDER', 'PROVIDER_ESCALATED'));

-- Un dictamen resuelto tiene autor y origen. Sin esto, la base admitiría un «aprobado» que nadie
-- firmó, que es exactamente lo que un expediente regulatorio no puede contener.
ALTER TABLE origination.prospect_document_files
    ADD CONSTRAINT chk_document_review_authored
        CHECK (review_status = 'PENDING_REVIEW'
            OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND verification_source IS NOT NULL));

-- Un rechazo siempre lleva motivo: sin él, el solicitante no sabe qué volver a subir.
ALTER TABLE origination.prospect_document_files
    ADD CONSTRAINT chk_document_rejection_reason
        CHECK (review_status <> 'REJECTED' OR (rejection_reason IS NOT NULL AND rejection_reason <> ''));

-- La consulta de la mesa: qué tengo pendiente por dictaminar. Parcial para que no crezca con el
-- histórico de lo ya resuelto, que es la mayor parte de la tabla con el tiempo.
CREATE INDEX idx_document_files_pending_review
    ON origination.prospect_document_files (uploaded_at)
    WHERE review_status = 'PENDING_REVIEW';
