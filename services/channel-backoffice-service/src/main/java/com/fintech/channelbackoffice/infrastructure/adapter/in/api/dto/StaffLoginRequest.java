package com.fintech.channelbackoffice.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record StaffLoginRequest(

        @NotBlank @Email
        String email,

        @NotBlank
        String password
) {}
