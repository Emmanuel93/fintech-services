package com.fintech.commission.application.service;

import com.fintech.commission.application.port.out.*;
import com.fintech.commission.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Core of the B2B2C distributor model: <strong>the commission goes against the payment</strong>, a
 * % of the interest actually collected, accrued per installment — never upfront against colocation
 * (CM-01). {@code PaymentApplied} doesn't carry the interest/principal split, so "interest collected"
 * is derived from the delta of {@code accruedInterestBalance} across consecutive
 * {@code balance-updated} deliveries (same shadow pattern as Accounting/T4).
 */
@Service
@Transactional
public class CommissionAccrualService {

    private static final Logger log = LoggerFactory.getLogger(CommissionAccrualService.class);

    private static final String PAYMENT_APPLIED  = "PAYMENT_APPLIED";
    private static final String PAYMENT_RETURNED = "PAYMENT_RETURNED";

    private final AccountBalanceShadowRepository shadowRepository;
    private final CreditPromoterAssignmentRepository assignmentRepository;
    private final CommissionPolicyRepository policyRepository;
    private final CommissionRecordRepository recordRepository;
    private final CommissionEventPublisher eventPublisher;

    public CommissionAccrualService(AccountBalanceShadowRepository shadowRepository,
                                     CreditPromoterAssignmentRepository assignmentRepository,
                                     CommissionPolicyRepository policyRepository,
                                     CommissionRecordRepository recordRepository,
                                     CommissionEventPublisher eventPublisher) {
        this.shadowRepository     = shadowRepository;
        this.assignmentRepository = assignmentRepository;
        this.policyRepository     = policyRepository;
        this.recordRepository     = recordRepository;
        this.eventPublisher       = eventPublisher;
    }

    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId,
                                 BigDecimal accruedInterestBalance, String triggerEvent, long balanceVersion) {
        AccountBalanceShadow shadow = shadowRepository.findById(creditAccountId)
                .orElseGet(() -> AccountBalanceShadow.init(creditAccountId));
        BigDecimal interestDelta = shadow.applyAndComputeInterestCollected(accruedInterestBalance, balanceVersion);
        if (interestDelta == null) {
            log.debug("Stale/duplicate balance-updated creditAccountId={} v={} — skipping", creditAccountId, balanceVersion);
            return;
        }
        shadowRepository.save(shadow);

        // CM-01: only PAYMENT_APPLIED with a real drop in accrued interest generates commission.
        if (PAYMENT_APPLIED.equals(triggerEvent) && interestDelta.signum() > 0) {
            accrueDistributorCommission(sourceEventId, creditAccountId, interestDelta);
        } else if (PAYMENT_RETURNED.equals(triggerEvent) && interestDelta.signum() < 0) {
            // CM-05: interest reinstated by a bounced payment — reverse the most recent accrual.
            // Known limitation: credit-portfolio's PAYMENT_RETURNED handling today restores the
            // amount to penaltyBalance, not accruedInterestBalance (documented "bucket TBD" there),
            // and there is no correlation id back to the original PAYMENT_APPLIED — so this branch
            // is correct-by-design but won't fire until that gap is closed upstream. Reversing the
            // single most recent ACCRUED record is a conservative, auditable approximation, not an
            // exact per-payment match.
            reverseMostRecentCommission(creditAccountId);
        }
    }

    private void accrueDistributorCommission(String sourceEventId, UUID creditAccountId, BigDecimal interestCollected) {
        if (recordRepository.existsBySourceEventId(sourceEventId)) return; // CR-04

        CreditPromoterAssignment assignment = assignmentRepository.findById(creditAccountId).orElse(null);
        if (assignment == null || !assignment.isActive()) {
            log.debug("No active CreditPromoterAssignment for creditAccountId={} — no beneficiary to pay (CM-07)",
                    creditAccountId);
            return;
        }

        CommissionPolicy policy = resolvePolicy(assignment.getProductType(), assignment.getBeneficiaryPartyId());
        if (policy == null) {
            log.warn("No ACTIVE CommissionPolicy for productType={} distributorPartyId={} — skipping accrual (CM-06)",
                    assignment.getProductType(), assignment.getBeneficiaryPartyId());
            return;
        }

        CommissionRecord record = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                creditAccountId, assignment.getBeneficiaryPartyId(), sourceEventId, interestCollected, policy.getRate());
        recordRepository.save(record);
        eventPublisher.publishCommissionAccrued(record);
        log.info("CommissionRecord accrued commissionId={} creditAccountId={} beneficiary={} basis={} rate={} amount={}",
                record.getCommissionId(), creditAccountId, assignment.getBeneficiaryPartyId(),
                interestCollected, policy.getRate(), record.getAmount());
    }

    private void reverseMostRecentCommission(UUID creditAccountId) {
        Optional<CommissionRecord> mostRecent = recordRepository
                .findFirstByCreditAccountIdAndStatusOrderByAccrualDateDesc(creditAccountId, CommissionRecordStatus.ACCRUED);
        if (mostRecent.isEmpty()) {
            log.debug("Interest reinstated for creditAccountId={} but no ACCRUED commission to reverse", creditAccountId);
            return;
        }
        CommissionRecord record = mostRecent.get();
        record.reverse();
        recordRepository.save(record);
        eventPublisher.publishCommissionReversed(record);
        log.info("CommissionRecord reversed commissionId={} creditAccountId={}", record.getCommissionId(), creditAccountId);
    }

    /** Distributor-specific rate wins over the product default (CP-01). */
    private CommissionPolicy resolvePolicy(String productType, UUID distributorPartyId) {
        return policyRepository.findActiveByProductAndTypeAndDistributor(
                        productType, CommissionType.DISTRIBUTOR_INTEREST_SHARE, distributorPartyId)
                .or(() -> policyRepository.findActiveDefaultByProductAndType(
                        productType, CommissionType.DISTRIBUTOR_INTEREST_SHARE))
                .orElse(null);
    }
}
