package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shape of {@code commission.lot-payout-requested}: pay a lot of commissions, net of withholdings, to its beneficiary.
 * Only what the order needs is read; the taxes travel for accounting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommissionLotPayoutPayload(
        UUID batchId,
        String itemRef,
        int attempt,
        UUID beneficiaryPartyId,
        String productType,
        String period,
        BigDecimal netAmount,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryTaxId,
        String concept,
        Instant occurredOn
) {}
