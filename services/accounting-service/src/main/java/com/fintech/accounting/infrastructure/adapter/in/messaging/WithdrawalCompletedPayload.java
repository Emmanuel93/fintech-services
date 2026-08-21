package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/** Inbound {@code wallet.withdrawal-completed} — retiro que liquida el pasivo de fondos de clientes. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawalCompletedPayload(
        UUID withdrawalId,
        UUID creditAccountId,
        UUID obligorPartyId,
        BigDecimal amount,
        java.time.Instant occurredOn
) {}
