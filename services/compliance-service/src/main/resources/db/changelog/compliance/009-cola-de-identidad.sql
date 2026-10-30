--liquibase formatted sql
--changeset compliance:009-cola-de-identidad author:system
--comment La cola de identidad de la mesa de cumplimiento: lo que espera dictamen, del más viejo al más nuevo.

-- ─────────────────────────────────────────────────────────────────────────────
-- Mientras no haya proveedor de KYC, TODA alta queda en identidad IN_REVIEW: por esta cola pasa el
-- 100 % del volumen. 002/003 indexaron la cola de listas (POTENTIAL_MATCH) y no ésta, porque
-- entonces no había quién la leyera — la mesa sólo existía como endpoint por la red interna.
--
-- Parcial a propósito: VERIFIED y REJECTED son la mayoría con el tiempo y nadie los busca como
-- trabajo pendiente. Indexar sólo lo vivo mantiene el índice del tamaño de la cola, no del padrón.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE INDEX idx_pv_identidad_pendiente
    ON compliance.person_verifications (created_at)
    WHERE identity_status IN ('PENDING', 'IN_REVIEW');
