package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.domain.StaffRole;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record ChangeStaffRolesRequest(

        @NotEmpty(message = "Un empleado debe conservar al menos un rol; para retirarle el acceso usa la baja")
        Set<StaffRole> roles
) {}
