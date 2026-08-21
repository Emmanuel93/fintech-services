package com.fintech.collections.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Inbound from credit-portfolio (topic {@code credit-portfolio.installment-upcoming}, new). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallmentUpcomingPayload(
        UUID installmentId,
        UUID creditAccountId,
        LocalDate dueDate,
        BigDecimal totalAmount
) {}
