package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.AccountBalanceShadowRepository;
import com.fintech.accounting.domain.AccountBalanceShadow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaAccountBalanceShadowRepository
        extends JpaRepository<AccountBalanceShadow, UUID>, AccountBalanceShadowRepository {
}
