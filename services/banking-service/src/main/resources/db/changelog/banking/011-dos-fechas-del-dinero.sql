--liquibase formatted sql
--changeset banking-service:011-dos-fechas-del-dinero
--comment The cash book keeps both dates of each peso: the bank's (SPEI operation date) and the portfolio's.

-- business_date is the SPEI operation date: the day the bank books the movement, the one Bancos (1101) follows.
-- portfolio_date is the day the portfolio dated the same money (a payment received at 19:00 on the 30th is the
-- portfolio's 30th and the bank's 1st). Accounting shows the difference as items in transit, one by one.
ALTER TABLE banking.internal_movements ADD COLUMN portfolio_date DATE;
