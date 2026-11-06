package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Inbound {@code banking.cash-movement-recorded}: one movement of treasury's cash book. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CashMovementRecordedPayload(String eventId, UUID movementId, String movementType, String direction,
                                          BigDecimal amount, LocalDate businessDate, String reference,
                                          String sourceEventId, LocalDate portfolioDate, String trackingKey) {}
