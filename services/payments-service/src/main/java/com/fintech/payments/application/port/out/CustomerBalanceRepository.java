package com.fintech.payments.application.port.out;

import com.fintech.payments.domain.balance.BalanceMovementType;
import com.fintech.payments.domain.balance.CustomerBalance;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The payers' balances in favor and the ledger of their movements. */
public interface CustomerBalanceRepository {

    Optional<CustomerBalance> findByParty(UUID partyId);

    CustomerBalance save(CustomerBalance balance);

    boolean movementExists(BalanceMovementType type, String sourceKey);

    CustomerBalanceMovement save(CustomerBalanceMovement movement);

    List<CustomerBalanceMovement> movementsOf(UUID partyId);
}
