package com.fintech.commission.application.service;

import com.fintech.commission.application.BalanceMove;
import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.AccountBalanceShadowRepository;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionPolicyRepository;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.domain.AccountBalanceShadow;
import com.fintech.commission.domain.CommissionPolicy;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionSource;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.CreditPromoterAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Core of the B2B2C distributor model: <strong>the commission goes against the payment</strong>, a % of the interest
 * actually collected, accrued per payment — never upfront against colocation (CM-01).
 *
 * <p>The interest a payment collected comes from its breakdown ({@code interestDelta}): the portfolio knows how it split
 * each payment. Deriving it from the drop in the interest balance between consecutive {@code balance-updated}
 * deliveries (the shadow) stays only for producers that do not send the breakdown; it broke as soon as two facts of a
 * loan crossed.
 *
 * <p>A returned payment (CM-05) names its payment order, and the commission of that payment is reversed in proportion
 * to the interest the return restores ({@link CommissionRecord#reversalOf}). Before, it reversed «the most recent
 * accrual» of the loan, whatever payment that was, and whole.
 */
@Service
@Transactional
public class CommissionAccrualService {

    private static final Logger log = LoggerFactory.getLogger(CommissionAccrualService.class);

    /** The order a payment's accruals give back a partial return: by the portfolio's day, then as they accrued. */
    private static final Comparator<CommissionRecord> OLDEST_FIRST =
            Comparator.comparing(CommissionRecord::getValueDate, Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
                      .thenComparing(CommissionRecord::getAccrualDate, Comparator.nullsFirst(Comparator.<Instant>naturalOrder()))
                      .thenComparing(CommissionRecord::getCommissionId);

    private final AccountBalanceShadowRepository shadowRepository;
    private final CreditPromoterAssignmentRepository assignmentRepository;
    private final CommissionPolicyRepository policyRepository;
    private final CommissionRecordRepository recordRepository;
    private final CommissionEventPublisher eventPublisher;
    private final CommissionProperties properties;

    public CommissionAccrualService(AccountBalanceShadowRepository shadowRepository,
                                    CreditPromoterAssignmentRepository assignmentRepository,
                                    CommissionPolicyRepository policyRepository,
                                    CommissionRecordRepository recordRepository,
                                    CommissionEventPublisher eventPublisher,
                                    CommissionProperties properties) {
        this.shadowRepository     = shadowRepository;
        this.assignmentRepository = assignmentRepository;
        this.policyRepository     = policyRepository;
        this.recordRepository     = recordRepository;
        this.eventPublisher       = eventPublisher;
        this.properties           = properties;
    }

    public void onBalanceUpdated(BalanceMove move) {
        BigDecimal shadowDrop = applyToShadow(move);
        if (move.isNetting()) {
            recordNetting(move);
            return;
        }
        if (move.isNettingReversal()) {
            reverseNetting(move);
            return;
        }
        if (move.isPayment()) {
            // On a line whose commission is netted against her statements the payment itself accrues nothing: her
            // commission came, to the cent, in its own COMMISSION_NETTED move.
            if (move.isNetted()) return;
            BigDecimal collected = move.interestDelta() != null ? move.interestDelta().negate() : shadowDrop;
            if (collected != null && collected.signum() > 0) accrue(move, collected);
        } else if (move.isReturn()) {
            BigDecimal restored = move.interestDelta() != null ? move.interestDelta()
                    : shadowDrop != null ? shadowDrop.negate() : null;
            if (restored != null && restored.signum() > 0) reverse(move, restored);
        }
    }

    /** The drop in the interest balance since the last delivery (the legacy measure); null for a stale delivery. */
    private BigDecimal applyToShadow(BalanceMove move) {
        if (move.accruedInterestBalance() == null) return null;
        AccountBalanceShadow shadow = shadowRepository.findById(move.creditAccountId())
                .orElseGet(() -> AccountBalanceShadow.init(move.creditAccountId()));
        BigDecimal drop = shadow.applyAndComputeInterestCollected(move.accruedInterestBalance(), move.balanceVersion());
        if (drop == null) {
            log.debug("Stale/duplicate balance-updated creditAccountId={} v={}", move.creditAccountId(), move.balanceVersion());
            return null;
        }
        shadowRepository.save(shadow);
        return drop;
    }

    private void accrue(BalanceMove move, BigDecimal interestCollected) {
        if (recordRepository.existsBySourceEventId(move.eventId())) return; // CR-04

        UUID creditAccountId = move.creditAccountId();
        CreditPromoterAssignment assignment = assignmentRepository.findById(creditAccountId).orElse(null);
        if (assignment == null || !assignment.isActive()) {
            log.debug("No active CreditPromoterAssignment for creditAccountId={} — no beneficiary to pay (CM-07)",
                    creditAccountId);
            return;
        }

        CommissionPolicy policy = resolvePolicy(assignment.getProductType(), assignment.getBeneficiaryPartyId());
        if (policy == null) {
            log.warn("Sin política de comisión ACTIVE productType={} distributorPartyId={} — no se devenga (CM-06)",
                    assignment.getProductType(), assignment.getBeneficiaryPartyId());
            return;
        }

        CommissionRecord record = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(creditAccountId, assignment.getBeneficiaryPartyId(), assignment.getProductType(),
                        move.eventId(), move.paymentOrder(), move.valueDate()),
                interestCollected, policy.getRate(), properties.taxRules().ivaRate());
        recordRepository.save(record);
        eventPublisher.publishCommissionAccrued(record);
        log.info("Comisión devengada commissionId={} creditAccountId={} beneficiaria={} producto={} pago={} base={} "
                        + "tasa={} comisión={} IVA={}",
                record.getCommissionId(), creditAccountId, assignment.getBeneficiaryPartyId(), record.getProductType(),
                record.getPaymentRef(), interestCollected, policy.getRate(), record.getAmount(), record.getIvaAmount());
    }

    /**
     * The commission of the returned payment, taken back in the share of the interest the return restores.
     *
     * <p>A payment can have accrued more than once on the same loan (its interest collected in two moves). The interest
     * the return restores is then given back oldest accrual first, each original taking back at most what is left of
     * it: the restored interest is spent once, not once per original. With several originals each reversal is keyed
     * {@code <event>:<original>}, so a redelivered return is recognised by those keys too.
     */
    private void reverse(BalanceMove move, BigDecimal interestRestored) {
        if (recordRepository.existsBySourceEventId(move.eventId())) return;
        String order = move.paymentOrder();
        List<CommissionRecord> ofPayment = order == null ? List.of() : recordRepository.findByPaymentRef(order).stream()
                .filter(r -> move.creditAccountId().equals(r.getCreditAccountId()))
                .toList();
        String keyPrefix = move.eventId() + ":";
        if (ofPayment.stream().anyMatch(r -> r.getSourceEventId().startsWith(keyPrefix))) return;
        // A netted commission is undone by its own COMMISSION_NETTED_REVERSED move, not by the interest the return restores.
        List<CommissionRecord> originals = ofPayment.stream().filter(r -> !r.isReversal() && !r.isCompensated())
                .sorted(OLDEST_FIRST).toList();
        if (originals.isEmpty()) {
            log.info("Pago devuelto sin comisión que revertir creditAccountId={} pago={}", move.creditAccountId(), order);
            return;
        }
        BigDecimal toRestore = interestRestored;
        for (CommissionRecord original : originals) {
            if (toRestore.signum() <= 0) break;
            CommissionRecord.Reversed already = CommissionRecord.Reversed.NONE;
            for (CommissionRecord r : ofPayment) {
                if (original.getCommissionId().equals(r.getReversesCommissionId())) already = already.plus(r);
            }
            CommissionRecord reversal = CommissionRecord.reversalOf(original, toRestore, already,
                    originals.size() == 1 ? move.eventId() : keyPrefix + original.getCommissionId(),
                    move.valueDate());
            if (reversal == null) continue;
            toRestore = toRestore.add(reversal.getBasis()); // a reversal's basis is negative: the interest it gave back
            recordRepository.save(reversal);
            eventPublisher.publishCommissionReversed(reversal);
            log.info("Comisión revertida por pago devuelto reversa={} original={} creditAccountId={} pago={} "
                            + "interés restituido={} comisión={} IVA={} (original {})",
                    reversal.getCommissionId(), original.getCommissionId(), move.creditAccountId(), order,
                    interestRestored, reversal.getAmount(), reversal.getIvaAmount(), original.getStatus());
        }
    }

    /**
     * The distributor's commission the portfolio netted against one of her statements: recorded as it was settled
     * (her flat share of what her clients paid that statement, at the tier of the day the payment landed), compensated
     * from birth. Accounting books the expense against what it owes her, and the portfolio's own move settles it.
     */
    private void recordNetting(BalanceMove move) {
        BalanceMove.Netting n = move.commission();
        if (n == null || n.amount() == null || n.amount().signum() <= 0) return;
        if (recordRepository.existsBySourceEventId(move.eventId())) return; // CR-04
        CreditPromoterAssignment assignment = assignmentRepository.findById(move.creditAccountId()).orElse(null);
        if (assignment == null || !assignment.isActive()) {
            log.warn("Comisión neteada sin promotora asignada creditAccountId={} ciclo={} importe={} — no se registra (CM-07)",
                    move.creditAccountId(), n.cycleNumber(), n.amount());
            return;
        }
        CommissionRecord record = CommissionRecord.netted(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(move.creditAccountId(), assignment.getBeneficiaryPartyId(), assignment.getProductType(),
                        move.eventId(), n.paymentOrder(), move.valueDate()),
                n.basis(), n.rate(), n.amount(), n.daysLate(), n.paymentDueDate(), n.cycleNumber());
        recordRepository.save(record);
        eventPublisher.publishCommissionAccrued(record);
        log.info("Comisión neteada commissionId={} creditAccountId={} beneficiaria={} ciclo={} vence={} díasTarde={} base={} "
                        + "tasa={} comisión={}",
                record.getCommissionId(), move.creditAccountId(), assignment.getBeneficiaryPartyId(), n.cycleNumber(),
                n.paymentDueDate(), n.daysLate(), n.basis(), n.rate(), record.getAmount());
    }

    /** A netted commission undone because its payment came back: the same statement, negated, keyed by the undo. */
    private void reverseNetting(BalanceMove move) {
        if (recordRepository.existsBySourceEventId(move.eventId())) return;
        BalanceMove.Netting n = move.commission();
        String order = n != null && n.paymentOrder() != null ? n.paymentOrder() : move.paymentOrder();
        if (order == null) return;
        List<CommissionRecord> ofPayment = recordRepository.findByPaymentRef(order).stream()
                .filter(r -> move.creditAccountId().equals(r.getCreditAccountId()) && r.isCompensated()).toList();
        for (CommissionRecord original : ofPayment) {
            if (original.isReversal()) continue;
            if (n != null && original.getCycleNumber() != null && original.getCycleNumber() != n.cycleNumber()) continue;
            boolean undone = ofPayment.stream().anyMatch(r -> original.getCommissionId().equals(r.getReversesCommissionId()));
            if (undone) continue;
            CommissionRecord reversal = CommissionRecord.nettedReversalOf(original, move.eventId(), move.valueDate());
            recordRepository.save(reversal);
            eventPublisher.publishCommissionReversed(reversal);
            log.info("Comisión neteada revertida por pago devuelto reversa={} original={} creditAccountId={} pago={} ciclo={} comisión={}",
                    reversal.getCommissionId(), original.getCommissionId(), move.creditAccountId(), order,
                    original.getCycleNumber(), reversal.getAmount());
        }
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
