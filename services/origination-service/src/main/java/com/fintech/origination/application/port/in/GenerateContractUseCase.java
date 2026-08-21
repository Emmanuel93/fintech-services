package com.fintech.origination.application.port.in;

import com.fintech.origination.application.GenerateContractCommand;
import com.fintech.origination.domain.CreditApplication;

public interface GenerateContractUseCase {
    CreditApplication generate(GenerateContractCommand command);
}
