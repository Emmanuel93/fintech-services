package com.fintech.identity.infrastructure.adapter.in.api.dto;

import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.StaffStatus;
import com.fintech.identity.domain.StaffUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vista de directorio. Expone el estado de la cuenta, nunca el hash de la contraseña. */
public record StaffUserResponse(
        UUID staffUserId,
        String email,
        String fullName,
        String curp,
        EmployeeType employeeType,
        UUID distributorPartyId,
        List<String> roles,
        StaffStatus status,
        Instant lastLoginAt,
        Instant createdAt
) {
    public static StaffUserResponse from(StaffUser u) {
        return new StaffUserResponse(
                u.getStaffUserId(), u.getEmail(), u.getFullName(), u.getCurp(),
                u.getEmployeeType(), u.getDistributorPartyId(), u.roleNames(),
                u.getStatus(), u.getLastLoginAt(), u.getCreatedAt());
    }
}
