package com.fintech.identity.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeStaffPasswordRequest(

        @NotBlank @Size(min = 12, message = "La contraseña de un empleado debe tener al menos 12 caracteres")
        String password
) {}
