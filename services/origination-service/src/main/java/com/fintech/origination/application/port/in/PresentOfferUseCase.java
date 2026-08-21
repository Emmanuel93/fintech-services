package com.fintech.origination.application.port.in;

import com.fintech.origination.application.PresentOfferCommand;
import com.fintech.origination.domain.CreditApplication;

public interface PresentOfferUseCase {
    CreditApplication present(PresentOfferCommand command);
}
