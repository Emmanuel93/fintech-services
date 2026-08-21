package com.fintech.party.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

public record PartyBlacklistedPayload(
        UUID    partyId,
        String  reason,
        String  sourceList,
        Instant blacklistedAt
) {}
