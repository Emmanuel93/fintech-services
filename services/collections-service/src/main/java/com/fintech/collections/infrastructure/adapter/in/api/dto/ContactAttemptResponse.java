package com.fintech.collections.infrastructure.adapter.in.api.dto;

import com.fintech.collections.domain.ContactAttempt;

import java.time.Instant;
import java.util.UUID;

public record ContactAttemptResponse(
        UUID attemptId,
        UUID caseId,
        String channel,
        String result,
        /** MANUAL | AUTOMATIC — la pantalla los distingue: no es lo mismo una llamada que un SMS. */
        String origin,
        String agentId,
        /** El mensaje en notifications, para poder ir del caso al texto que salió. */
        UUID notificationId,
        Integer dunningStep,
        Instant attemptedAt
) {
    public static ContactAttemptResponse from(ContactAttempt a) {
        return new ContactAttemptResponse(a.getAttemptId(), a.getCaseId(), a.getChannel().name(),
                a.getResult().name(), a.getOrigin().name(), a.getAgentId(),
                a.getNotificationId(), a.getDunningStep(), a.getAttemptedAt());
    }
}
