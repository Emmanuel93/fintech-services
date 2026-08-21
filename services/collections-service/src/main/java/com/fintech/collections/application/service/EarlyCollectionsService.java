package com.fintech.collections.application.service;

import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * EC-*: cobranza temprana. Reacts to credit-portfolio.installment-upcoming (T-lead_days before
 * dueDate) — never opens a CollectionCase, the account is not delinquent yet.
 */
@Service
@Transactional
public class EarlyCollectionsService {

    private static final Logger log = LoggerFactory.getLogger(EarlyCollectionsService.class);

    private final AccountBalanceSnapshotRepository snapshotRepository;
    private final CollectionsEventPublisher eventPublisher;

    public EarlyCollectionsService(AccountBalanceSnapshotRepository snapshotRepository,
                                    CollectionsEventPublisher eventPublisher) {
        this.snapshotRepository = snapshotRepository;
        this.eventPublisher     = eventPublisher;
    }

    /**
     * EC-01/EC-02/EC-03: always reminds, no risk segmentation in v1. InstallmentUpcoming (like
     * InstallmentDue elsewhere in this system) doesn't carry obligorPartyId — resolved from the
     * local snapshot, same pattern Wallet already uses for the same event family.
     */
    public void onInstallmentUpcoming(UUID creditAccountId, LocalDate dueDate, BigDecimal installmentAmount) {
        UUID obligorPartyId = snapshotRepository.findById(creditAccountId)
                .map(s -> s.getObligorPartyId())
                .orElse(null);
        if (obligorPartyId == null) {
            log.warn("InstallmentUpcoming for unknown creditAccountId={} — no snapshot, skipping reminder", creditAccountId);
            return;
        }
        log.info("PreDueReminder triggered creditAccountId={} dueDate={} amount={}",
                creditAccountId, dueDate, installmentAmount);
        eventPublisher.publishPreDueReminderTriggered(creditAccountId, obligorPartyId, dueDate, installmentAmount);
    }
}
