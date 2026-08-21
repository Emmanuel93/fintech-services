package com.fintech.identity.application.port.in;

import com.fintech.identity.application.StaffLoginCommand;
import com.fintech.identity.application.StaffSession;

import java.util.UUID;

public interface StaffLoginUseCase {

    StaffSession login(StaffLoginCommand command);

    /** Renueva la sesión releyendo los roles vigentes del empleado. */
    StaffSession refresh(String rawRefreshToken);

    void logout(UUID staffUserId);
}
