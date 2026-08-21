package com.fintech.identity.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        /** Identificador de dispositivo opcional enviado por la app cliente. */
        String deviceId
) {}
