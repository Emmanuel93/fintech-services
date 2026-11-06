package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.UUID;

/** {@code credit-portfolio.account-day-closed}: el interés del día de una cuenta retenida ya está y cuadra. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountDayClosedMessage(LocalDate businessDate, UUID creditAccountId) {}
