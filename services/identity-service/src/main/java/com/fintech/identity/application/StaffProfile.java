package com.fintech.identity.application;

import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.StaffUser;

import java.util.List;
import java.util.UUID;

/**
 * Identidad del empleado tal como la consume el backoffice: lo justo para pintar la sesión y
 * resolver el RBAC del lado del BFF. Nunca incluye el hash de la contraseña.
 */
public record StaffProfile(
        UUID staffUserId,
        String email,
        String fullName,
        EmployeeType employeeType,
        UUID distributorPartyId,
        List<String> roles
) {
    public static StaffProfile from(StaffUser user) {
        return new StaffProfile(
                user.getStaffUserId(),
                user.getEmail(),
                user.getFullName(),
                user.getEmployeeType(),
                user.getDistributorPartyId(),
                user.roleNames());
    }
}
