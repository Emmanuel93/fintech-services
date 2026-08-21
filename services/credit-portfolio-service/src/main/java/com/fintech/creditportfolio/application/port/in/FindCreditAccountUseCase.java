package com.fintech.creditportfolio.application.port.in;

import com.fintech.creditportfolio.domain.CreditAccount;

import java.util.UUID;

public interface FindCreditAccountUseCase {
    CreditAccount getById(UUID creditAccountId);
}
