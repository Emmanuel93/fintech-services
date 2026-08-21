package com.fintech.identity.application;

import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.StaffRole;

import java.util.Set;
import java.util.UUID;

public record CreateStaffCommand(
        String email,
        String fullName,

        /** CURP del empleado; opcional, la exige la bitácora de auditoría para identificarlo. */
        String curp,

        EmployeeType employeeType,

        /** Obligatorio para COLABORADOR_EMPRESARIAL, nulo para INTERNO. */
        UUID distributorPartyId,

        Set<StaffRole> roles,
        String password
) {}
