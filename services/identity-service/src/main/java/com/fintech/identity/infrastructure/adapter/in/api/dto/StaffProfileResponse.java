package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.application.StaffProfile;
import com.fintech.identity.domain.EmployeeType;

import java.util.List;
import java.util.UUID;

/** Identidad del empleado dentro de la sesión. Nunca incluye estado de credencial ni hashes. */
public record StaffProfileResponse(
        UUID staffUserId,
        String email,
        String fullName,
        EmployeeType employeeType,
        UUID distributorPartyId,
        List<String> roles
) {
    public static StaffProfileResponse from(StaffProfile p) {
        return new StaffProfileResponse(
                p.staffUserId(), p.email(), p.fullName(),
                p.employeeType(), p.distributorPartyId(), p.roles());
    }
}
