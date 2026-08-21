package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.StaffRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreateStaffRequest(

        @NotBlank @Email
        String email,

        @NotBlank
        String fullName,

        /**
         * CURP del empleado. Opcional en el contrato porque el personal ya dado de alta no la tiene
         * y forzarla rompería toda integración existente; se valida el formato cuando viene.
         */
        @Pattern(regexp = "^[A-Za-z]{4}\\d{6}[HMhm][A-Za-z]{5}[A-Za-z0-9]\\d$",
                 message = "Invalid CURP format")
        String curp,

        @NotNull
        EmployeeType employeeType,

        /** Obligatorio para COLABORADOR_EMPRESARIAL, nulo para INTERNO (SU-04). */
        UUID distributorPartyId,

        @NotEmpty
        Set<StaffRole> roles,

        @NotBlank @Size(min = 12, message = "La contraseña de un empleado debe tener al menos 12 caracteres")
        String password
) {}
