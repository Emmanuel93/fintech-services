package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Inbound {@code credit-portfolio.day-closed}: the portfolio closed {@code businessDate}. {@code heldAccounts} are the
 * ones charges left out of the day's accrual; the day is complete for them once they are released.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioDayClosedPayload(LocalDate businessDate, int accounts, List<UUID> heldAccounts) {}
