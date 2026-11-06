package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound {@code commission.commission-reversed}: what a returned payment took back of the commission it names, as
 * positive amounts. {@code commissionId} is the reversal's own id (one per return); {@code occurredOn}: when it was
 * reversed (the voucher's date).
 */
public record CommissionReversedPayload(
        UUID commissionId,
        UUID reversesCommissionId,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        String productType,
        BigDecimal amount,
        BigDecimal ivaAmount,
        Instant occurredOn,
        /** NETTED when settled against the distributor's statement; null when paid in a lot. */
        String settlement
) {}
