package com.fintech.commission.infrastructure.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound {@code commission.lot-payout-requested}: pay a lot by SPEI. Disbursement orders {@code netAmount} to
 * {@code beneficiaryAccount}; accounting moves the gross out of commissions payable (2120) into the SPEI in flight
 * (2103, item {@code itemRef}) and the withholdings to the SAT's. {@code attempt} keys the order: a lot whose order
 * failed is requested again with the next one.
 */
public record LotPayoutRequestedPayload(
        UUID batchId,
        String itemRef,
        int attempt,
        UUID beneficiaryPartyId,
        String productType,
        String period,
        BigDecimal subtotal,
        BigDecimal ivaAmount,
        BigDecimal isrWithheld,
        BigDecimal ivaWithheld,
        BigDecimal grossAmount,
        BigDecimal netAmount,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryTaxId,
        String concept,
        Instant occurredOn
) {}
