--liquibase formatted sql
--changeset stp-service:018-montos-de-escenario-del-stub
--comment The amounts a test armed for the stub's scenarios: with scenario-amounts ARMED only these play their cents' scenario.

-- Used ONLY by the stub of the lower environments (fintech.stp.gateway.mode=stub): empty in production, where the stub
-- refuses to start. The local stack arms here the amounts its end-to-end scenarios chose; any other order settles,
-- whatever its cents, so a commission lot or a funding payment never fails by arithmetic.
CREATE TABLE stp.stub_scenario_amounts (
    amount   NUMERIC(19, 2)           NOT NULL,
    armed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT stub_scenario_amounts_pk PRIMARY KEY (amount)
);
