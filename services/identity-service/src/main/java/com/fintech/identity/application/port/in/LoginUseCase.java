package com.fintech.identity.application.port.in;

import com.fintech.identity.application.LoginCommand;
import com.fintech.identity.application.LoginResult;

public interface LoginUseCase {

    LoginResult login(LoginCommand command);
}
