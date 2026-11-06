package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.domain.balance.CustomerBalance;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataCustomerBalances extends JpaRepository<CustomerBalance, UUID> {
}

interface SpringDataCustomerBalanceMovements extends JpaRepository<CustomerBalanceMovement, UUID> {
    boolean existsByMovementTypeAndSourceKey(String movementType, String sourceKey);

    List<CustomerBalanceMovement> findByPartyIdOrderByOccurredAtAsc(UUID partyId);
}
