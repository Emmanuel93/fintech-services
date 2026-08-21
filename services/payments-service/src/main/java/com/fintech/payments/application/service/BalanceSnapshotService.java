package com.fintech.payments.application.service;

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class BalanceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BalanceSnapshotService.class);

    private final AccountBalanceSnapshotRepository snapshotRepository;

    public BalanceSnapshotService(AccountBalanceSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    /** Called on credit-account-activated: register the account with zero balances. */
    public void initSnapshot(UUID creditAccountId, UUID obligorPartyId, BigDecimal creditLimit) {
        if (snapshotRepository.existsByCreditAccountId(creditAccountId)) {
            log.debug("Snapshot already exists for creditAccountId={} — skipping init", creditAccountId);
            return;
        }
        AccountBalanceSnapshot snapshot = AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, creditLimit);
        snapshotRepository.save(snapshot);
        log.info("AccountBalanceSnapshot initialized creditAccountId={}", creditAccountId);
    }

    /** Called on balance-updated: upsert the snapshot with latest balances from credit-portfolio. */
    public void upsert(UUID creditAccountId, UUID obligorPartyId,
                       BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                       BigDecimal penaltyBalance, BigDecimal availableCredit,
                       BigDecimal totalDebt, long balanceVersion, String accountStatus) {

        AccountBalanceSnapshot snapshot = snapshotRepository.findByCreditAccountId(creditAccountId)
                .orElseGet(() -> {
                    log.warn("No snapshot for creditAccountId={} during balance-updated — creating late", creditAccountId);
                    return AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, availableCredit);
                });

        snapshot.update(principalBalance, accruedInterestBalance, penaltyBalance,
                availableCredit, totalDebt, balanceVersion, accountStatus);
        snapshotRepository.save(snapshot);

        log.debug("Snapshot updated creditAccountId={} totalDebt={} version={} status={}",
                creditAccountId, totalDebt, balanceVersion, accountStatus);
    }

    @Transactional(readOnly = true)
    public Optional<AccountBalanceSnapshot> findByCreditAccountId(UUID creditAccountId) {
        return snapshotRepository.findByCreditAccountId(creditAccountId);
    }
}
