package com.fintech.party.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/** Un rol de party otorgado o revocado. {@code actor} = quién lo hizo (null si fue el sistema). */
public record PartyRolePayload(
        UUID    partyId,
        String  roleType,
        String  actor,
        Instant occurredAt
) {}
