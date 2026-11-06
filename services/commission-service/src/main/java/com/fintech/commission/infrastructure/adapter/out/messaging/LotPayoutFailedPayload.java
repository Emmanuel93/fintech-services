package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound {@code commission.lot-payout-failed}: the lot's order failed or came back. Accounting undoes the attempt's
 * move to the SPEI in flight: the gross is owed to the beneficiary again (2120).
 */
public record LotPayoutFailedPayload(
        UUID batchId,
        String itemRef,
        int attempt,
        UUID beneficiaryPartyId,
        String productType,
        BigDecimal subtotal,
        BigDecimal ivaAmount,
        BigDecimal isrWithheld,
        BigDecimal ivaWithheld,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        String reason,
        Instant occurredOn
) {}
