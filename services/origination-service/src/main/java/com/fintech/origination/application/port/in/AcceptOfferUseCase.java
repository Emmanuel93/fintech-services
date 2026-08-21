package com.fintech.origination.application.port.in;

import com.fintech.origination.domain.CreditApplication;

import java.util.UUID;

public interface AcceptOfferUseCase {
    CreditApplication accept(UUID applicationId);
}
