package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Inbound {@code banking.funding-movement-recorded}: a movement of a funding source, with the balances it left. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundingMovementPayload(String eventId, UUID fundingMovementId, UUID fundingSourceId, String sourceKind,
                                     String counterpartyName, String contractReference, String movementType,
                                     BigDecimal amount, LocalDate valueDate, LocalDate accruedFrom,
                                     LocalDate accruedThrough, BigDecimal contributedAfter, BigDecimal principalAfter,
                                     BigDecimal interestPayableAfter, String sourceStatus, Integer termMonths,
                                     Instant occurredOn) {}
