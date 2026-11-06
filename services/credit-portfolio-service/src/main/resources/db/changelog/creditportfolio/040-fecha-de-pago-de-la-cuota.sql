--liquibase formatted sql
--changeset credit-portfolio:040-fecha-de-pago-de-la-cuota
--comment The business day an installment became fully paid.
-- A placement paid ahead (all its installments covered by one payment) was reported as "settled in the period" in
-- every statement until its last scheduled due date, because the only date the schedule had was the due date.
-- Null for installments paid before this column existed.
ALTER TABLE credit_portfolio.installments ADD COLUMN IF NOT EXISTS paid_on DATE;
