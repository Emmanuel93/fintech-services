package com.fintech.commission;

import com.fintech.commission.application.port.out.*;
import com.fintech.commission.application.service.CommissionAccrualService;
import com.fintech.commission.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CommissionAccrualServiceTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock CreditPromoterAssignmentRepository assignmentRepository;
    @Mock CommissionPolicyRepository policyRepository;
    @Mock CommissionRecordRepository recordRepository;
    @Mock CommissionEventPublisher eventPublisher;

    CommissionAccrualService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID distributorPartyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CommissionAccrualService(shadowRepository, assignmentRepository, policyRepository,
                recordRepository, eventPublisher);
    }

    private CreditPromoterAssignment assignment() {
        return CreditPromoterAssignment.create(creditAccountId, distributorPartyId, "DISTRIBUTOR_LINE");
    }

    private CommissionPolicy defaultPolicy(BigDecimal rate) {
        return CommissionPolicy.create("DISTRIBUTOR_LINE", null, CommissionType.DISTRIBUTOR_INTEREST_SHARE, rate, 1);
    }

    @Test
    void paymentApplied_withInterestDrop_accruesCommission() {
        // shadow starts at 1000 interest, drops to 700 → 300 collected
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("1000"), 1L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(recordRepository.existsBySourceEventId("evt-1")).willReturn(false);
        given(assignmentRepository.findById(creditAccountId)).willReturn(Optional.of(assignment()));
        given(policyRepository.findActiveByProductAndTypeAndDistributor(
                "DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, distributorPartyId))
                .willReturn(Optional.empty());
        given(policyRepository.findActiveDefaultByProductAndType("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE))
                .willReturn(Optional.of(defaultPolicy(new BigDecimal("0.30"))));
        given(recordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onBalanceUpdated("evt-1", creditAccountId, new BigDecimal("700"), "PAYMENT_APPLIED", 2L);

        ArgumentCaptor<CommissionRecord> captor = ArgumentCaptor.forClass(CommissionRecord.class);
        then(recordRepository).should().save(captor.capture());
        assertThat(captor.getValue().getBasis()).isEqualByComparingTo("300");
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("90.00"); // 300 * 0.30
        assertThat(captor.getValue().getBeneficiaryPartyId()).isEqualTo(distributorPartyId);
        then(eventPublisher).should().publishCommissionAccrued(any());
    }

    @Test
    void distributorSpecificRate_winsOverDefault() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("1000"), 1L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(recordRepository.existsBySourceEventId("evt-1")).willReturn(false);
        given(assignmentRepository.findById(creditAccountId)).willReturn(Optional.of(assignment()));
        given(policyRepository.findActiveByProductAndTypeAndDistributor(
                "DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, distributorPartyId))
                .willReturn(Optional.of(CommissionPolicy.create("DISTRIBUTOR_LINE", distributorPartyId,
                        CommissionType.DISTRIBUTOR_INTEREST_SHARE, new BigDecimal("0.50"), 1)));
        given(recordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onBalanceUpdated("evt-1", creditAccountId, new BigDecimal("700"), "PAYMENT_APPLIED", 2L);

        ArgumentCaptor<CommissionRecord> captor = ArgumentCaptor.forClass(CommissionRecord.class);
        then(recordRepository).should().save(captor.capture());
        assertThat(captor.getValue().getRate()).isEqualByComparingTo("0.50");
        then(policyRepository).should(never()).findActiveDefaultByProductAndType(any(), any());
    }

    @Test
    void noPromoterAssignment_skipsAccrual_CM07() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("1000"), 1L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(recordRepository.existsBySourceEventId("evt-1")).willReturn(false);
        given(assignmentRepository.findById(creditAccountId)).willReturn(Optional.empty());

        service.onBalanceUpdated("evt-1", creditAccountId, new BigDecimal("700"), "PAYMENT_APPLIED", 2L);

        then(recordRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void noActivePolicy_skipsAccrual_CM06() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("1000"), 1L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(recordRepository.existsBySourceEventId("evt-1")).willReturn(false);
        given(assignmentRepository.findById(creditAccountId)).willReturn(Optional.of(assignment()));
        given(policyRepository.findActiveByProductAndTypeAndDistributor(any(), any(), any())).willReturn(Optional.empty());
        given(policyRepository.findActiveDefaultByProductAndType(any(), any())).willReturn(Optional.empty());

        service.onBalanceUpdated("evt-1", creditAccountId, new BigDecimal("700"), "PAYMENT_APPLIED", 2L);

        then(recordRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void chargeTriggerEvent_neverAccrues_onlyPaymentApplied_CM01() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("700"), 1L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // interest INCREASES (a new charge), not a payment — must never accrue
        service.onBalanceUpdated("evt-1", creditAccountId, new BigDecimal("1000"), "CHARGE_ORDINARY_INTEREST", 2L);

        then(recordRepository).shouldHaveNoInteractions();
        then(assignmentRepository).shouldHaveNoInteractions();
    }

    @Test
    void staleBalanceVersion_isSkipped() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("500"), 5L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));

        service.onBalanceUpdated("evt-old", creditAccountId, new BigDecimal("300"), "PAYMENT_APPLIED", 3L);

        then(shadowRepository).should(never()).save(any());
        then(recordRepository).shouldHaveNoInteractions();
    }

    @Test
    void paymentReturned_reversesMostRecentAccrual_CM05() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("700"), 1L); // was 700
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CommissionRecord mostRecent = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                creditAccountId, distributorPartyId, "evt-original", new BigDecimal("300"), new BigDecimal("0.30"));
        given(recordRepository.findFirstByCreditAccountIdAndStatusOrderByAccrualDateDesc(
                creditAccountId, CommissionRecordStatus.ACCRUED)).willReturn(Optional.of(mostRecent));
        given(recordRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // interest goes back UP (1000) — payment bounced
        service.onBalanceUpdated("evt-2", creditAccountId, new BigDecimal("1000"), "PAYMENT_RETURNED", 2L);

        assertThat(mostRecent.getStatus()).isEqualTo(CommissionRecordStatus.REVERSED);
        then(eventPublisher).should().publishCommissionReversed(mostRecent);
    }

    @Test
    void paymentReturned_noAccruedRecord_isNoOp() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(creditAccountId);
        shadow.applyAndComputeInterestCollected(new BigDecimal("700"), 1L);
        given(shadowRepository.findById(creditAccountId)).willReturn(Optional.of(shadow));
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(recordRepository.findFirstByCreditAccountIdAndStatusOrderByAccrualDateDesc(any(), any()))
                .willReturn(Optional.empty());

        service.onBalanceUpdated("evt-2", creditAccountId, new BigDecimal("1000"), "PAYMENT_RETURNED", 2L);

        then(eventPublisher).shouldHaveNoInteractions();
    }
}
