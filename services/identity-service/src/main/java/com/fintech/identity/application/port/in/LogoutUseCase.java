package com.fintech.identity.application.port.in;

import java.util.UUID;

public interface LogoutUseCase {

    void logout(UUID partyId);
}
