package com.fintech.wallet.infrastructure.adapter.out.persistence;

import com.fintech.wallet.application.port.out.WalletWithdrawalRepository;
import com.fintech.wallet.domain.WalletWithdrawal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JpaWalletWithdrawalRepository
        extends JpaRepository<WalletWithdrawal, UUID>, WalletWithdrawalRepository {

    @Override
    List<WalletWithdrawal> findByCreditAccountId(UUID creditAccountId);
}
