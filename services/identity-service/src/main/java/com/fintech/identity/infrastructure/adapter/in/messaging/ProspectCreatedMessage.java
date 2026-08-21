package com.fintech.identity.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProspectCreatedMessage(
        UUID prospectId,
        String username,
        String password
) {}
