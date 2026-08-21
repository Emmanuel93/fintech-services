package com.fintech.identity.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record WhitelistEntryRequest(
        @NotBlank String cidr,
        String label
) {}
