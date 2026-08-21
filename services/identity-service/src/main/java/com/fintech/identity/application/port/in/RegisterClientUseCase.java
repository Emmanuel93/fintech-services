package com.fintech.identity.application.port.in;

import com.fintech.identity.application.ClientRegistrationResult;
import java.time.Instant;
import java.util.List;

public interface RegisterClientUseCase {
    ClientRegistrationResult registerClient(String clientId, String clientName,
                                            List<String> roles, Instant expiresAt);
}
