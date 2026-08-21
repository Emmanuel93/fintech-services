package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound {@code collections.pre-due-reminder-triggered} — NT-12: alimentado por un cron que ya
 * existe en credit-portfolio (UpcomingInstallmentJob), Notifications no agrega ninguno propio.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreDueReminderTriggeredPayload(
        UUID creditAccountId,
        UUID obligorPartyId,
        LocalDate dueDate,
        BigDecimal installmentAmount
) {}
