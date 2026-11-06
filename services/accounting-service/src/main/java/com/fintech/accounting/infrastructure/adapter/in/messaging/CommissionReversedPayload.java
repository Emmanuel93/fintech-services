package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inbound {@code commission.commission-reversed}: what a returned payment took back of the commission it names, as
 * positive amounts. {@code commissionId} is the reversal's own id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommissionReversedPayload(
        UUID commissionId,
        UUID reversesCommissionId,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        String productType,
        BigDecimal amount,
        BigDecimal ivaAmount,
        Instant occurredOn,
        /** NETTED when settled against the distributor's statement; null on older producers. */
        String settlement
) {}
