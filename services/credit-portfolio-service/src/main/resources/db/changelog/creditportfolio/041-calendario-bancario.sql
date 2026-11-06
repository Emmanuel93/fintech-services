--liquibase formatted sql
--changeset credit-portfolio:041-calendario-bancario
--comment The business calendar, as closing publishes it: the days that differ from the Monday-to-Friday rule.

-- A loan's installment falls due on the next business day when its date is a weekend or a bank holiday: the client
-- pays then without being late, without moratorium and without a collections call. The portfolio dates its plans
-- with this calendar; closing owns it and publishes it (closing.business-calendar-day).
CREATE TABLE IF NOT EXISTS credit_portfolio.business_calendar_days (
    calendar_code  VARCHAR(20)  NOT NULL,
    day            DATE         NOT NULL,
    is_business    BOOLEAN      NOT NULL,
    label          VARCHAR(80),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_business_calendar_days PRIMARY KEY (calendar_code, day)
);
