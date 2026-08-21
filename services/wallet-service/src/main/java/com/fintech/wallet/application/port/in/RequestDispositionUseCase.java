package com.fintech.wallet.application.port.in;

import com.fintech.wallet.application.RequestDispositionCommand;

public interface RequestDispositionUseCase {
    void request(RequestDispositionCommand command);
}
