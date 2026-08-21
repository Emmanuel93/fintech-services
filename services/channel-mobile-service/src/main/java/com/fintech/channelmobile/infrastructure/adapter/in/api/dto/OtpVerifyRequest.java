package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpVerifyRequest(
        @NotBlank
        @Pattern(regexp = "^\\d{10}$", message = "El teléfono debe tener 10 dígitos")
        String phone,

        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "El código OTP debe tener 6 dígitos")
        String code
) {}
