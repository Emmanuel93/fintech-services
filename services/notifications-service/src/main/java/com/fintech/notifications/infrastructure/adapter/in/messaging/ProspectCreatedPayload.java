package com.fintech.notifications.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound {@code origination.prospect-created} — el único punto del sistema donde phone/email viajan. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectCreatedPayload(
        UUID prospectId,
        String firstName,
        String phone,
        String email
) {}
