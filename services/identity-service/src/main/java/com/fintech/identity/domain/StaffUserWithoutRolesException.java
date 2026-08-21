package com.fintech.identity.domain;

import com.fintech.shared.exception.DomainException;

/** SU-02: un empleado sin roles no puede existir — la baja se hace con {@code disable()}. */
public class StaffUserWithoutRolesException extends DomainException {

    public StaffUserWithoutRolesException() {
        super("AUTH_STAFF_WITHOUT_ROLES", "A staff user must have at least one role");
    }
}
