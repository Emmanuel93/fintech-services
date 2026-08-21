package com.fintech.wallet.infrastructure.adapter.out.persistence;

import com.fintech.wallet.application.port.out.WalletMovementRepository;
import com.fintech.wallet.domain.WalletMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaWalletMovementRepository
        extends JpaRepository<WalletMovement, UUID>, WalletMovementRepository {

    List<WalletMovement> findByCreditAccountIdOrderByCreatedAtDesc(UUID creditAccountId);

    @Override
    default List<WalletMovement> findByCreditAccountId(UUID creditAccountId) {
        return findByCreditAccountIdOrderByCreatedAtDesc(creditAccountId);
    }
}
