--liquibase formatted sql
--changeset collections:013-quien-propone-el-convenio
--comment Who proposed each agreement, so that whoever proposes cannot authorize (maker-checker).

-- Until now an agreement recorded only who authorized it, and that name came in the request body: the
-- console could write any name, and the same person could propose and authorize. The proposer comes
-- from the session that proposed; agreements proposed before this change keep it empty.
ALTER TABLE collections.collection_agreements
    ADD COLUMN IF NOT EXISTS proposed_by VARCHAR(80);
