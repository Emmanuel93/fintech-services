package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** Inbound from collections ({@code collections.agreement-executed}). Only RESTRUCTURE marks forbearance. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectionAgreementExecutedPayload(
        UUID creditAccountId,
        String type
) {}
