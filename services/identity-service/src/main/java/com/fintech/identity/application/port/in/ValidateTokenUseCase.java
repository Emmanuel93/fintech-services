package com.fintech.identity.application.port.in;

import com.fintech.identity.application.TokenValidationResult;

public interface ValidateTokenUseCase {

    TokenValidationResult validate(String bearerToken);
}
