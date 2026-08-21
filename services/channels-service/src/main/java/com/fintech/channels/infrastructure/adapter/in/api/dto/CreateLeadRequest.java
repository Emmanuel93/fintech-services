package com.fintech.channels.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateLeadRequest(
        @NotNull UUID channelId,
        @NotBlank String intentType,
        @NotBlank String firstName,
        String lastName1,
        String phone,
        String email,
        UUID promoterPartyId
) {}
