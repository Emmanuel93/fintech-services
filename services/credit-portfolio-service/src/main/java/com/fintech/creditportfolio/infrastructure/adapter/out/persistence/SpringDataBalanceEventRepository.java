package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.BalanceEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataBalanceEventRepository extends JpaRepository<BalanceEvent, UUID> {
    boolean existsBySourceEventId(String sourceEventId);
    long countByCreditAccountId(UUID creditAccountId);
}
