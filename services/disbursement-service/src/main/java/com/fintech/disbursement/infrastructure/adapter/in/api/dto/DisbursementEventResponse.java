package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import com.fintech.disbursement.domain.DisbursementEvent;

import java.time.Instant;
import java.util.UUID;

public record DisbursementEventResponse(
        UUID eventId,
        String fromStatus,
        String toStatus,
        String reasonCode,
        String detail,
        String actor,
        Instant occurredAt
) {

    public static DisbursementEventResponse from(DisbursementEvent event) {
        return new DisbursementEventResponse(event.getEventId(), event.getFromStatus(),
                event.getToStatus(), event.getReasonCode(), event.getDetail(),
                event.getActor(), event.getOccurredAt());
    }
}
