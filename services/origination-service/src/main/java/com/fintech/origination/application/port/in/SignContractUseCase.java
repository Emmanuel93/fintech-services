package com.fintech.origination.application.port.in;

import com.fintech.origination.application.SignContractCommand;
import com.fintech.origination.domain.CreditApplication;

public interface SignContractUseCase {
    CreditApplication sign(SignContractCommand command);
}
