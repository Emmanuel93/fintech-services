package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.CreditAccountProgressRepository;
import com.fintech.notifications.domain.CreditAccountProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaCreditAccountProgressRepository
        extends JpaRepository<CreditAccountProgress, UUID>, CreditAccountProgressRepository {
}
