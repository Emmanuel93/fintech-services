package com.fintech.origination.application.port.in;

import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.StartCreditApplicationResult;

public interface StartCreditApplicationUseCase {

    StartCreditApplicationResult start(StartCreditApplicationCommand command);
}
