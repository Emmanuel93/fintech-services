package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound {@code credit-portfolio.balance-updated} — solo nos importa accountStatus=SETTLED. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String accountStatus
) {}
