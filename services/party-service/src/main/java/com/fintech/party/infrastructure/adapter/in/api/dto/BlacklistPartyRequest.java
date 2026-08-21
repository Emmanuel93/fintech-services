package com.fintech.party.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BlacklistPartyRequest(
        @NotBlank
        @Size(max = 300)
        String reason,

        String sourceList
) {}
