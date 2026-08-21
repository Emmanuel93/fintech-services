package com.fintech.identity.application.port.in;

import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffStatus;
import com.fintech.identity.domain.StaffUser;

import java.util.List;
import java.util.UUID;

public interface FindStaffUseCase {

    StaffUser getById(UUID staffUserId);

    /**
     * Directorio completo con filtros opcionales. Sin paginación a propósito: el staff se cuenta en
     * decenas, no en miles — a diferencia de clientes y cuentas, que sí la necesitan.
     */
    List<StaffUser> find(StaffStatus status, StaffRole role);
}
