package com.fintech.charges.application.service;

import com.fintech.charges.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.charges.domain.AccountBalanceSnapshot;
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

    /** Called on credit-account-activated. Idempotent: skips if snapshot already exists. */
    public void initSnapshot(UUID creditAccountId, UUID obligorPartyId, BigDecimal creditLimit) {
        if (snapshotRepository.existsByCreditAccountId(creditAccountId)) {
            log.debug("Balance snapshot already exists for creditAccountId={} — skipping", creditAccountId);
            return;
        }
        AccountBalanceSnapshot snapshot = AccountBalanceSnapshot.init(
                creditAccountId, obligorPartyId, creditLimit != null ? creditLimit : BigDecimal.ZERO);
        snapshotRepository.save(snapshot);
        log.info("Balance snapshot initialised creditAccountId={} creditLimit={}", creditAccountId, creditLimit);
    }

    /**
     * Upsert on every balance-updated event.
     * Creates the snapshot late (race condition: charge event before activation) when it doesn't exist yet.
     */
    public void upsert(UUID creditAccountId, UUID obligorPartyId,
                       BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                       BigDecimal penaltyBalance, BigDecimal availableCredit, BigDecimal totalDebt,
                       BigDecimal creditLimit, long balanceVersion, String accountStatus) {
        AccountBalanceSnapshot snapshot = snapshotRepository.findByCreditAccountId(creditAccountId)
                .orElseGet(() -> {
                    log.warn("Balance snapshot missing at upsert for creditAccountId={} — creating late", creditAccountId);
                    return AccountBalanceSnapshot.init(creditAccountId, obligorPartyId,
                            creditLimit != null ? creditLimit : BigDecimal.ZERO);
                });
        snapshot.update(principalBalance, accruedInterestBalance, penaltyBalance,
                availableCredit, totalDebt, balanceVersion, accountStatus);
        snapshotRepository.save(snapshot);
        log.debug("Balance snapshot updated creditAccountId={} status={} version={}",
                creditAccountId, accountStatus, balanceVersion);
    }

    @Transactional(readOnly = true)
    public Optional<AccountBalanceSnapshot> findByCreditAccountId(UUID creditAccountId) {
        return snapshotRepository.findByCreditAccountId(creditAccountId);
    }
}
