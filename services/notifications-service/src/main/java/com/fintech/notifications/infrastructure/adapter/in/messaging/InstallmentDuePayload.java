package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entrante {@code credit-portfolio.installment-due} — lo publica InstallmentDueJob
 * cuando una mensualidad llega a su fecha y sigue sin cubrirse.
 *
 * <p>No trae {@code obligorPartyId}: el evento habla de la mensualidad, no de la
 * persona. Se resuelve del progreso de la cuenta, que ya se guardó al activarla.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstallmentDuePayload(
        UUID installmentId,
        UUID creditAccountId,
        Integer installmentNumber,
        LocalDate dueDate,
        BigDecimal totalAmount
) {}
