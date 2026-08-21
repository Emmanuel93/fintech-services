package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.AccountBalanceShadowRepository;
import com.fintech.commission.domain.AccountBalanceShadow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaAccountBalanceShadowRepository
        extends JpaRepository<AccountBalanceShadow, UUID>, AccountBalanceShadowRepository {
}
