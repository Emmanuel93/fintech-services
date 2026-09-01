package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Forma de {@code credit-portfolio.delinquency-status-updated}.
 *
 * <p>{@code overduePrincipal} es lo que hace útil este evento: sin él, este servicio tendría que
 * adivinar la base del moratorio — y adivinarla es exactamente lo que hacía cuando usaba el saldo
 * completo del crédito.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DelinquencyStatusUpdatedPayload(UUID creditAccountId,
                                              UUID obligorPartyId,
                                              String contractNumber,
                                              int daysDelinquent,
                                              BigDecimal overduePrincipal,
                                              LocalDate oldestDueDate) {}
