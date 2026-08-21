package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.CreditAccountProgress;

import java.util.Optional;
import java.util.UUID;

public interface CreditAccountProgressRepository {
    Optional<CreditAccountProgress> findById(UUID creditAccountId);
    CreditAccountProgress save(CreditAccountProgress progress);
}
