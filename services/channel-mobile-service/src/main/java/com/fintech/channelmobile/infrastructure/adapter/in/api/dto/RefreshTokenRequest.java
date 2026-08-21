package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(@NotBlank String refreshToken) {}
