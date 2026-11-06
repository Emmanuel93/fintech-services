package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inbound {@code commission.commission-accrued}: the commission (before IVA), its IVA, who it is owed to and for which
 * product. {@code occurredOn}: noon of the payment's portfolio day, the voucher's date.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommissionAccruedPayload(
        UUID commissionId,
        String commissionType,
        UUID creditAccountId,
        UUID beneficiaryPartyId,
        String productType,
        BigDecimal amount,
        BigDecimal ivaAmount,
        Instant occurredOn,
        /** NETTED when settled against the distributor's statement; null on older producers. */
        String settlement
) {}
