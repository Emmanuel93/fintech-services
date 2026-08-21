package com.fintech.commission.application.port.out;

import com.fintech.commission.domain.AccountBalanceShadow;

import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceShadowRepository {
    Optional<AccountBalanceShadow> findById(UUID creditAccountId);
    AccountBalanceShadow save(AccountBalanceShadow shadow);
}
