package com.fintech.payments.application.service;

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.CollectionAccountDirectory;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class BalanceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BalanceSnapshotService.class);

    private final AccountBalanceSnapshotRepository snapshotRepository;
    private final CollectionAccountDirectory collectionAccounts;

    public BalanceSnapshotService(AccountBalanceSnapshotRepository snapshotRepository,
                                  CollectionAccountDirectory collectionAccounts) {
        this.snapshotRepository = snapshotRepository;
        this.collectionAccounts = collectionAccounts;
    }

    /** Called on credit-account-activated: register the account with zero balances. */
    public void initSnapshot(UUID creditAccountId, UUID obligorPartyId, BigDecimal creditLimit) {
        initSnapshot(creditAccountId, obligorPartyId, creditLimit, null, null, null);
    }

    /**
     * Called on credit-account-activated: register the loan with what applying a received credit needs (its age, its
     * payment reference, its product), and make sure its obligor has a collection CLABE to pay it to.
     */
    public void initSnapshot(UUID creditAccountId, UUID obligorPartyId, BigDecimal creditLimit,
                             Instant activatedAt, Long paymentReference, String productType) {
        if (snapshotRepository.existsByCreditAccountId(creditAccountId)) {
            // A balance-updated consumed first created it, or the activation is redelivered: describe what is missing.
            snapshotRepository.findByCreditAccountId(creditAccountId)
                    .filter(s -> (s.getPaymentReference() == null && paymentReference != null)
                            || (s.getActivatedAt() == null && activatedAt != null))
                    .ifPresent(s -> {
                        s.describe(activatedAt, paymentReference, productType);
                        snapshotRepository.save(s);
                    });
        } else {
            AccountBalanceSnapshot snapshot = AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, creditLimit);
            snapshot.describe(activatedAt, paymentReference, productType);
            snapshotRepository.save(snapshot);
            log.info("AccountBalanceSnapshot initialized creditAccountId={} reference={}", creditAccountId, paymentReference);
        }
        collectionAccounts.request(obligorPartyId);
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
