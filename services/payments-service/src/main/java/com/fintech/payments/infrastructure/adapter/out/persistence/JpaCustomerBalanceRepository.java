package com.fintech.payments.infrastructure.adapter.out.persistence;

import com.fintech.payments.application.port.out.CustomerBalanceRepository;
import com.fintech.payments.domain.balance.BalanceMovementType;
import com.fintech.payments.domain.balance.CustomerBalance;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCustomerBalanceRepository implements CustomerBalanceRepository {

    private final SpringDataCustomerBalances balances;
    private final SpringDataCustomerBalanceMovements movements;

    JpaCustomerBalanceRepository(SpringDataCustomerBalances balances, SpringDataCustomerBalanceMovements movements) {
        this.balances = balances;
        this.movements = movements;
    }

    @Override public Optional<CustomerBalance> findByParty(UUID partyId) { return balances.findById(partyId); }
    @Override public CustomerBalance save(CustomerBalance balance) { return balances.save(balance); }
    @Override public boolean movementExists(BalanceMovementType type, String sourceKey) {
        return movements.existsByMovementTypeAndSourceKey(type.name(), sourceKey);
    }
    @Override public CustomerBalanceMovement save(CustomerBalanceMovement movement) { return movements.save(movement); }
    @Override public List<CustomerBalanceMovement> movementsOf(UUID partyId) {
        return movements.findByPartyIdOrderByOccurredAtAsc(partyId);
    }
}
