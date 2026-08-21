package com.fintech.collections.application.service;

import com.fintech.collections.application.port.in.GetCollectionCaseUseCase;
import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * CM-*: opens/escalates/closes CollectionCase from credit-portfolio's delinquency signals.
 * Never modifies balances — only decides collections strategy.
 */
@Service
@Transactional
public class CaseManagementService implements GetCollectionCaseUseCase {

    private static final Logger log = LoggerFactory.getLogger(CaseManagementService.class);

    private final CollectionCaseRepository caseRepository;
    private final AccountBalanceSnapshotRepository snapshotRepository;
    private final CollectionsEventPublisher eventPublisher;

    public CaseManagementService(CollectionCaseRepository caseRepository,
                                  AccountBalanceSnapshotRepository snapshotRepository,
                                  CollectionsEventPublisher eventPublisher) {
        this.caseRepository     = caseRepository;
        this.snapshotRepository = snapshotRepository;
        this.eventPublisher     = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionCase getById(UUID caseId) {
        return caseRepository.findById(caseId)
                .orElseThrow(() -> new CollectionCaseNotFoundException(caseId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public CollectionCase getByCreditAccountId(UUID creditAccountId) {
        return caseRepository.findActiveByCreditAccountId(creditAccountId)
                .orElseThrow(() -> new CollectionCaseNotFoundException(creditAccountId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CollectionCase> search(CaseStatus status, DelinquencyBucket bucket, String productType,
                                        String assignedAgentId, Integer minDaysDelinquent, Pageable pageable) {
        // Un texto en blanco no es un filtro: se normaliza a null para que la consulta lo ignore,
        // en vez de buscar el producto llamado "".
        String type  = (productType == null || productType.isBlank()) ? null : productType;
        String agent = (assignedAgentId == null || assignedAgentId.isBlank()) ? null : assignedAgentId;
        return caseRepository.search(status, bucket, type, agent, minDaysDelinquent, pageable);
    }

    /** Seeds the local snapshot at activation — the only event that carries productType. */
    public void onCreditAccountActivated(UUID creditAccountId, UUID obligorPartyId, String productType) {
        if (snapshotRepository.findById(creditAccountId).isPresent()) return; // idempotent
        snapshotRepository.save(AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, productType));
    }

    /**
     * CM-01/CM-02/CM-03: DelinquencyStatusUpdated — open a case (bucket>=B1_30), escalate an
     * existing one, or close it when daysDelinquent drops back to 0. credit-portfolio has no
     * separate "DelinquencyCleared" event — a cleared account is just this event with days=0.
     * productType/totalDebt come from the local snapshot — DelinquencyStatusUpdated itself
     * doesn't carry either.
     */
    public void onDelinquencyStatusUpdated(UUID creditAccountId, UUID obligorPartyId, int daysDelinquent) {
        DelinquencyBucket bucket = DelinquencyBucket.fromDaysDelinquent(daysDelinquent);
        var existing = caseRepository.findActiveByCreditAccountId(creditAccountId);

        if (bucket == DelinquencyBucket.CURRENT) {
            existing.ifPresent(this::closeCleared);
            return;
        }

        if (existing.isEmpty()) {
            var snapshot = snapshotRepository.findById(creditAccountId);
            BigDecimal totalDebt = snapshot.map(AccountBalanceSnapshot::getTotalDebt).orElse(BigDecimal.ZERO);
            String productType = snapshot.map(AccountBalanceSnapshot::getProductType).orElse("UNKNOWN");
            String strategy = CollectionStrategyResolver.resolve(bucket);
            CollectionCase c = CollectionCase.open(creditAccountId, obligorPartyId, productType,
                    daysDelinquent, totalDebt, strategy);
            caseRepository.save(c);
            log.info("CollectionCase opened caseId={} creditAccountId={} bucket={}", c.getCaseId(), creditAccountId, bucket);
            eventPublisher.publishCollectionCaseCreated(c);
            return;
        }

        CollectionCase c = existing.get();
        DelinquencyBucket previousBucket = c.getCurrentBucket();
        BigDecimal totalDebt = currentTotalDebt(creditAccountId);
        c.escalate(daysDelinquent, totalDebt, CollectionStrategyResolver.resolve(bucket));
        caseRepository.save(c);
        if (previousBucket != c.getCurrentBucket()) {
            log.info("CollectionCase escalated caseId={} {}->{}", c.getCaseId(), previousBucket, c.getCurrentBucket());
            eventPublisher.publishCollectionCaseEscalated(c, previousBucket.name());
        }
    }

    private void closeCleared(CollectionCase c) {
        c.close();
        caseRepository.save(c);
        log.info("CollectionCase closed (delinquency cleared) caseId={}", c.getCaseId());
    }

    /**
     * CM-05: accountStatus=SETTLED on a BalanceUpdated -> CLOSED. Like DelinquencyCleared,
     * credit-portfolio has no dedicated ProductSettled event — this is derived from
     * BalanceUpdated.accountStatus (see BalanceUpdatedListener).
     */
    public void onProductSettled(UUID creditAccountId) {
        caseRepository.findActiveByCreditAccountId(creditAccountId).ifPresent(this::closeCleared);
    }

    /** Local balance snapshot sync — feeds totalDebt (CollectionCase) and the write-off split. */
    public void onBalanceUpdated(UUID creditAccountId, UUID obligorPartyId,
                                  BigDecimal principalBalance, BigDecimal accruedInterestBalance,
                                  BigDecimal penaltyBalance, BigDecimal totalDebt, long balanceVersion) {
        AccountBalanceSnapshot snapshot = snapshotRepository.findById(creditAccountId)
                .orElseGet(() -> AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, "UNKNOWN"));
        snapshot.upsert(principalBalance, accruedInterestBalance, penaltyBalance, totalDebt, balanceVersion);
        snapshotRepository.save(snapshot);
    }

    private BigDecimal currentTotalDebt(UUID creditAccountId) {
        return snapshotRepository.findById(creditAccountId)
                .map(AccountBalanceSnapshot::getTotalDebt)
                .orElse(BigDecimal.ZERO);
    }
}
