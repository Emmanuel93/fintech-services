package com.fintech.wallet.infrastructure.adapter.out.persistence;

import com.fintech.wallet.application.port.out.WalletViewRepository;
import com.fintech.wallet.domain.WalletView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaWalletViewRepository
        extends JpaRepository<WalletView, UUID>, WalletViewRepository {

    @Override
    Optional<WalletView> findByCreditAccountId(UUID creditAccountId);

    @Override
    List<WalletView> findByObligorPartyId(UUID obligorPartyId);

    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WalletView w
               SET w.walletBalance = w.walletBalance + :amount,
                   w.lastUpdatedAt = CURRENT_TIMESTAMP
             WHERE w.creditAccountId = :id""")
    int creditWalletBalance(@Param("id") UUID creditAccountId, @Param("amount") BigDecimal amount);

    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WalletView w
               SET w.walletBalance = w.walletBalance - :amount,
                   w.lastUpdatedAt = CURRENT_TIMESTAMP
             WHERE w.creditAccountId = :id AND w.walletBalance >= :amount""")
    int debitWalletBalanceIfEnough(@Param("id") UUID creditAccountId, @Param("amount") BigDecimal amount);

    @Override
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE WalletView w
               SET w.principalBalance = :principalBalance,
                   w.accruedInterestBalance = :accruedInterestBalance,
                   w.penaltyBalance = :penaltyBalance,
                   w.availableCredit = :availableCredit,
                   w.totalDebt = :totalDebt,
                   w.status = :status,
                   w.balanceVersion = :balanceVersion,
                   w.lastUpdatedAt = CURRENT_TIMESTAMP
             WHERE w.creditAccountId = :id""")
    int updateBalanceColumns(@Param("id") UUID creditAccountId,
                             @Param("principalBalance") BigDecimal principalBalance,
                             @Param("accruedInterestBalance") BigDecimal accruedInterestBalance,
                             @Param("penaltyBalance") BigDecimal penaltyBalance,
                             @Param("availableCredit") BigDecimal availableCredit,
                             @Param("totalDebt") BigDecimal totalDebt,
                             @Param("status") String status,
                             @Param("balanceVersion") long balanceVersion);
}
