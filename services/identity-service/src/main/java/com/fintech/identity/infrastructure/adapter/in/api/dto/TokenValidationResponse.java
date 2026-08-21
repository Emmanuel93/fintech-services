package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.domain.Channel;

import java.util.List;
import java.util.UUID;

public record TokenValidationResponse(
        UUID partyId,
        List<String> roles,
        String deviceId,
        Channel channel
) {}
