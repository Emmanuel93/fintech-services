package com.fintech.commission;

import com.fintech.commission.application.BalanceMove;
import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.AccountBalanceShadowRepository;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionPolicyRepository;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.application.service.CommissionAccrualService;
import com.fintech.commission.domain.AccountBalanceShadow;
import com.fintech.commission.domain.CommissionPolicy;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionSource;
import com.fintech.commission.domain.CommissionType;
import com.fintech.commission.domain.CreditPromoterAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * The commission goes against the payment (CM-01): a share of the interest it collected, taken from the payment's
 * breakdown. A returned payment takes back the commission of that payment (CM-05), in the share of the interest it
 * restores.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionAccrualServiceTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock CreditPromoterAssignmentRepository assignmentRepository;
    @Mock CommissionPolicyRepository policyRepository;
    @Mock CommissionRecordRepository recordRepository;
    @Mock CommissionEventPublisher eventPublisher;

    CommissionAccrualService service;

    private final UUID loan = UUID.randomUUID();
    private final UUID distributor = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 4, 10);
    private final List<CommissionRecord> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CommissionAccrualService(shadowRepository, assignmentRepository, policyRepository,
                recordRepository, eventPublisher, new CommissionProperties());
        given(shadowRepository.findById(loan)).willReturn(Optional.empty());
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(assignmentRepository.findById(loan)).willReturn(Optional.of(
                CreditPromoterAssignment.create(loan, distributor, "DISTRIBUTOR_LINE")));
        given(policyRepository.findActiveByProductAndTypeAndDistributor(
                "DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE, distributor)).willReturn(Optional.empty());
        given(policyRepository.findActiveDefaultByProductAndType("DISTRIBUTOR_LINE", CommissionType.DISTRIBUTOR_INTEREST_SHARE))
                .willReturn(Optional.of(CommissionPolicy.create("DISTRIBUTOR_LINE", null,
                        CommissionType.DISTRIBUTOR_INTEREST_SHARE, new BigDecimal("0.30"), 1)));
        given(recordRepository.save(any())).willAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private BalanceMove paymentOf(String eventId, String order, String interestDelta) {
        return new BalanceMove(eventId, loan, BalanceMove.PAYMENT_APPLIED, 2L, BigDecimal.ZERO,
                new BigDecimal(interestDelta), order, DAY);
    }

    private BalanceMove returnOf(String eventId, String order, String interestRestored) {
        return new BalanceMove(eventId, loan, BalanceMove.PAYMENT_RETURNED, 3L, new BigDecimal("300"),
                new BigDecimal(interestRestored), "PAYMENT_RETURNED:" + order, DAY.plusDays(2));
    }

    private CommissionRecord commissionOf(String order, String basis) {
        return accrualOn(DAY, "evt-" + order, order, basis);
    }

    private CommissionRecord accrualOn(LocalDate day, String eventId, String order, String basis) {
        return CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(loan, distributor, "DISTRIBUTOR_LINE", eventId, order, day),
                new BigDecimal(basis), new BigDecimal("0.30"), new BigDecimal("0.16"));
    }

    @Test
    @DisplayName("the basis is the interest the payment's breakdown says it collected")
    void accruesOnTheBreakdown() {
        service.onBalanceUpdated(paymentOf("evt-1", "pago-1", "-300.00"));

        ArgumentCaptor<CommissionRecord> captor = ArgumentCaptor.forClass(CommissionRecord.class);
        then(recordRepository).should().save(captor.capture());
        CommissionRecord r = captor.getValue();
        assertThat(r.getBasis()).isEqualByComparingTo("300.00");
        assertThat(r.getAmount()).isEqualByComparingTo("90.00");
        assertThat(r.getIvaAmount()).isEqualByComparingTo("14.40");
        assertThat(r.getPaymentRef()).isEqualTo("pago-1");
        assertThat(r.getProductType()).isEqualTo("DISTRIBUTOR_LINE");
        assertThat(r.getPeriod()).isEqualTo("202604");
        then(eventPublisher).should().publishCommissionAccrued(r);
    }

    @Test
    @DisplayName("a payment that paid no interest (only principal) accrues nothing")
    void noInterestNoCommission() {
        service.onBalanceUpdated(paymentOf("evt-1", "pago-1", "0.00"));

        then(recordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("without the breakdown (an older producer), the drop of the interest balance since the last delivery")
    void legacyShadowMeasure() {
        AccountBalanceShadow shadow = AccountBalanceShadow.init(loan);
        shadow.applyAndComputeInterestCollected(new BigDecimal("1000"), 1L);
        given(shadowRepository.findById(loan)).willReturn(Optional.of(shadow));

        service.onBalanceUpdated(new BalanceMove("evt-1", loan, BalanceMove.PAYMENT_APPLIED, 2L, new BigDecimal("700"),
                null, "pago-1", DAY));

        assertThat(saved).singleElement().satisfies(r -> assertThat(r.getBasis()).isEqualByComparingTo("300"));
    }

    @Test
    @DisplayName("CR-04: a redelivered payment accrues once")
    void idempotent() {
        given(recordRepository.existsBySourceEventId("evt-1")).willReturn(true);

        service.onBalanceUpdated(paymentOf("evt-1", "pago-1", "-300.00"));

        then(recordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("CM-07: a loan with no distributor accrues nothing")
    void noAssignment() {
        given(assignmentRepository.findById(loan)).willReturn(Optional.empty());

        service.onBalanceUpdated(paymentOf("evt-1", "pago-1", "-300.00"));

        then(recordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("CM-06: no active policy for the product, nothing accrues")
    void noPolicy() {
        given(policyRepository.findActiveDefaultByProductAndType(any(), any())).willReturn(Optional.empty());

        service.onBalanceUpdated(paymentOf("evt-1", "pago-1", "-300.00"));

        then(recordRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("CM-05: a return takes back the commission of ITS payment, not the loan's most recent one")
    void reversesThePaymentItNames() {
        CommissionRecord ofFirst = commissionOf("pago-1", "1000");    // 300.00 + 48.00
        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(ofFirst));

        service.onBalanceUpdated(returnOf("evt-ret", "pago-1", "1000"));

        assertThat(saved).singleElement().satisfies(r -> {
            assertThat(r.getReversesCommissionId()).isEqualTo(ofFirst.getCommissionId());
            assertThat(r.getAmount()).isEqualByComparingTo("-300.00");
            assertThat(r.getIvaAmount()).isEqualByComparingTo("-48.00");
            assertThat(r.getPeriod()).isEqualTo("202604");
        });
        then(eventPublisher).should().publishCommissionReversed(saved.get(0));
    }

    @Test
    @DisplayName("a return that restores part of the interest takes back that part; a second one, the rest")
    void partialThenRest() {
        CommissionRecord original = commissionOf("pago-1", "1000");
        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(original));
        service.onBalanceUpdated(returnOf("evt-ret-1", "pago-1", "250"));
        assertThat(saved).singleElement().satisfies(r -> assertThat(r.getAmount()).isEqualByComparingTo("-75.00"));

        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(original, saved.get(0)));
        service.onBalanceUpdated(returnOf("evt-ret-2", "pago-1", "750"));

        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).getAmount()).isEqualByComparingTo("-225.00");
    }

    @Test
    @DisplayName("a payment that accrued twice gives the restored interest back oldest first, spending it once")
    void twoAccrualsOfOnePayment() {
        CommissionRecord first  = accrualOn(DAY, "evt-a", "pago-1", "600");             // 180.00 + 28.80
        CommissionRecord second = accrualOn(DAY.plusDays(1), "evt-b", "pago-1", "400"); // 120.00 + 19.20
        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(second, first));

        service.onBalanceUpdated(returnOf("evt-ret-1", "pago-1", "500"));

        assertThat(saved).singleElement().satisfies(r -> {
            assertThat(r.getReversesCommissionId()).isEqualTo(first.getCommissionId());
            assertThat(r.getAmount()).isEqualByComparingTo("-150.00");
            assertThat(r.getIvaAmount()).isEqualByComparingTo("-24.00");
            assertThat(r.getSourceEventId()).isEqualTo("evt-ret-1:" + first.getCommissionId());
        });

        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(second, first, saved.get(0)));
        service.onBalanceUpdated(returnOf("evt-ret-2", "pago-1", "500"));

        assertThat(saved).hasSize(3);
        assertThat(saved.get(1).getReversesCommissionId()).isEqualTo(first.getCommissionId());
        assertThat(saved.get(1).getAmount()).isEqualByComparingTo("-30.00");
        assertThat(saved.get(1).getIvaAmount()).isEqualByComparingTo("-4.80");
        assertThat(saved.get(2).getReversesCommissionId()).isEqualTo(second.getCommissionId());
        assertThat(saved.get(2).getAmount()).isEqualByComparingTo("-120.00");
        assertThat(saved.get(2).getIvaAmount()).isEqualByComparingTo("-19.20");
        assertThat(saved.stream().map(CommissionRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("30 %% of the 1,000 restored, once").isEqualByComparingTo("-300.00");
    }

    @Test
    @DisplayName("a redelivered partial return of a payment that accrued twice takes nothing back again")
    void redeliveredReturnOfTwoAccruals() {
        CommissionRecord first  = accrualOn(DAY, "evt-a", "pago-1", "600");
        CommissionRecord second = accrualOn(DAY.plusDays(1), "evt-b", "pago-1", "400");
        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(first, second));
        service.onBalanceUpdated(returnOf("evt-ret", "pago-1", "500"));
        assertThat(saved).hasSize(1);

        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(first, second, saved.get(0)));
        service.onBalanceUpdated(returnOf("evt-ret", "pago-1", "500"));

        assertThat(saved).hasSize(1);
        then(eventPublisher).should().publishCommissionReversed(saved.get(0));
    }

    @Test
    @DisplayName("the return of a payment that produced no commission changes nothing")
    void returnWithoutCommission() {
        given(recordRepository.findByPaymentRef("pago-9")).willReturn(List.of());

        service.onBalanceUpdated(returnOf("evt-ret", "pago-9", "100"));

        then(recordRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishCommissionReversed(any());
    }

    @Test
    @DisplayName("the return of another loan's payment with the same order is not this loan's")
    void returnOfAnotherLoan() {
        CommissionRecord other = CommissionRecord.accrue(CommissionType.DISTRIBUTOR_INTEREST_SHARE,
                new CommissionSource(UUID.randomUUID(), distributor, "DISTRIBUTOR_LINE", "evt-x", "pago-1", DAY),
                new BigDecimal("1000"), new BigDecimal("0.30"), new BigDecimal("0.16"));
        given(recordRepository.findByPaymentRef("pago-1")).willReturn(List.of(other));

        service.onBalanceUpdated(returnOf("evt-ret", "pago-1", "1000"));

        then(recordRepository).should(never()).save(any());
    }
}
