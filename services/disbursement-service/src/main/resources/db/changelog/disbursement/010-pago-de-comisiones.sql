--liquibase formatted sql
--changeset disbursement-service:010-pago-de-comisiones
--comment Commission lots are paid by the SPEI rail: their source type and the company that pays them.

-- A lot of commissions is paid to its beneficiary by SPEI, as a disposition is paid to its borrower: commission asks
-- for it (`commission.lot-payout-requested`), and the order goes out of the operation's account like the others.
ALTER TABLE disbursement.disbursement_orders DROP CONSTRAINT disbursement_orders_source_type_chk;
ALTER TABLE disbursement.disbursement_orders ADD CONSTRAINT disbursement_orders_source_type_chk
    CHECK (source_type IN ('DISPOSITION', 'WITHDRAWAL', 'SURPLUS_RETURN', 'ACCOUNT_VERIFICATION', 'API', 'MANUAL',
                           'COMMISSION'));

-- The company that pays the commissions is the operation's, as for the portfolio and the wallet (008).
INSERT INTO disbursement.company_mappings
    (company_mapping_id, source_system, source_key, company_id, enabled, created_at)
VALUES
    ('7c1d0000-0000-4000-8000-000000000003', 'commission', '*',
     '9b1d0000-0000-4000-8000-000000000001', TRUE, NOW())
ON CONFLICT DO NOTHING;
