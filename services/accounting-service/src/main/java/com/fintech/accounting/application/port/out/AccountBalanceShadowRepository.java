package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.AccountBalanceShadow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceShadowRepository {
    Optional<AccountBalanceShadow> findById(UUID creditAccountId);
    List<AccountBalanceShadow> findAll();
    AccountBalanceShadow save(AccountBalanceShadow shadow);
}
