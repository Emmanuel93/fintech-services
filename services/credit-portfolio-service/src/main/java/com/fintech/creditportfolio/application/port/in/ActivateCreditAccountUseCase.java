package com.fintech.creditportfolio.application.port.in;

import com.fintech.creditportfolio.application.CreateCreditAccountCommand;
import com.fintech.creditportfolio.domain.CreditAccount;

public interface ActivateCreditAccountUseCase {
    /** Creates a CreditAccount from the origination snapshot and runs the full activation flow. */
    CreditAccount activate(CreateCreditAccountCommand command);
}
