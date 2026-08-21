package com.fintech.identity.application.port.in;

import java.util.UUID;

public interface DisableMfaUseCase {

    void disable(UUID partyId, String totpCode);
}
