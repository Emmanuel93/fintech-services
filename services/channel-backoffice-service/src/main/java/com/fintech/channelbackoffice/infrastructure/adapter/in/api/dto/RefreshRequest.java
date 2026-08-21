package com.fintech.channelbackoffice.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(@NotBlank String refreshToken) {}
