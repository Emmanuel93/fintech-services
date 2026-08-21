package com.fintech.origination.application.port.in;

import com.fintech.origination.domain.CreditApplication;

import java.util.UUID;

public interface RejectOfferUseCase {
    CreditApplication reject(UUID applicationId);
}
