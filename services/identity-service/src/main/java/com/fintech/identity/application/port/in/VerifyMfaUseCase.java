package com.fintech.identity.application.port.in;

import com.fintech.identity.application.MfaVerifyCommand;
import com.fintech.identity.application.TokenPair;

public interface VerifyMfaUseCase {

    TokenPair verify(MfaVerifyCommand command);
}
