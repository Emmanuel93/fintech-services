package com.fintech.identity.application.port.in;

import com.fintech.identity.application.TokenPair;

public interface RefreshTokenUseCase {

    TokenPair refresh(String rawRefreshToken);
}
