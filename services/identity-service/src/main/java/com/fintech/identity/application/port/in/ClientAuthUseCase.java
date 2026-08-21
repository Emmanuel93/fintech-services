package com.fintech.identity.application.port.in;

import com.fintech.identity.application.TokenPair;

public interface ClientAuthUseCase {
    TokenPair authenticateClient(String clientId, String clientSecret, String requestIp);
}
