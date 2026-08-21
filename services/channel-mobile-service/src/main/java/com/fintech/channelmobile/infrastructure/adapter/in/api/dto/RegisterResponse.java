package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

public record RegisterResponse(
        boolean success,
        String userId,
        String message
) {}
