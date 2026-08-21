package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(

        @NotBlank
        @Pattern(regexp = "^\\d{10}$", message = "El teléfono debe tener 10 dígitos")
        String phone,

        @NotBlank
        String password,

        String deviceId
) {}
