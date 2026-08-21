package com.fintech.identity.application.port.in;

import com.fintech.identity.application.CreateStaffCommand;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffUser;

import java.util.Set;
import java.util.UUID;

public interface ManageStaffUseCase {

    StaffUser create(CreateStaffCommand command);

    StaffUser changeRoles(UUID staffUserId, Set<StaffRole> roles);

    StaffUser changePassword(UUID staffUserId, String newPassword);

    StaffUser suspend(UUID staffUserId);

    StaffUser reactivate(UUID staffUserId);

    /** Baja lógica: el registro se conserva porque la bitácora y las comisiones lo referencian. */
    StaffUser disable(UUID staffUserId);
}
