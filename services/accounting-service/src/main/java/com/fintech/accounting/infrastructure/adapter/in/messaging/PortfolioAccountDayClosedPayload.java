package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** Inbound {@code credit-portfolio.account-day-closed}: an account held out of {@code businessDate} accrued it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioAccountDayClosedPayload(LocalDate businessDate, UUID creditAccountId) {}
