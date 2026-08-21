-- T6 Commission prerequisite: propagate the channel promoter/distributor attribution end-to-end
-- (channels.CustomerIntent.promoterCode -> CreditApplication -> CreditProductCreationRequested ->
-- credit-portfolio.CreditAccountActivated) so Commission can attribute the credit to a beneficiary.
ALTER TABLE origination.credit_applications
    ADD COLUMN IF NOT EXISTS promoter_code VARCHAR(100);
