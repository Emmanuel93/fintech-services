package com.fintech.commission;

import com.fintech.commission.application.BalanceMove;
import com.fintech.commission.application.BalanceMove.Netting;
import com.fintech.commission.application.CommissionProperties;
import com.fintech.commission.application.port.out.AccountBalanceShadowRepository;
import com.fintech.commission.application.port.out.CommissionEventPublisher;
import com.fintech.commission.application.port.out.CommissionPolicyRepository;
import com.fintech.commission.application.port.out.CommissionRecordRepository;
import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.application.service.CommissionAccrualService;
import com.fintech.commission.domain.CommissionRecord;
import com.fintech.commission.domain.CommissionRecordStatus;
import com.fintech.commission.domain.CreditPromoterAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;

/**
 * The distributor's commission netted against her statement, as the portfolio settles it: recorded compensated from
 * birth, at the tier of the day the payment landed; her payments accrue nothing else; undone only when its payment
 * comes back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionNettingRecordsTest {

    @Mock AccountBalanceShadowRepository shadowRepository;
    @Mock CreditPromoterAssignmentRepository assignmentRepository;
    @Mock CommissionPolicyRepository policyRepository;
    @Mock CommissionRecordRepository recordRepository;
    @Mock CommissionEventPublisher eventPublisher;

    CommissionAccrualService service;
    private final UUID line = UUID.randomUUID();
    private final UUID distributor = UUID.randomUUID();
    private final List<CommissionRecord> saved = new ArrayList<>();
    private static final LocalDate DUE = LocalDate.of(2026, 3, 3);

    @BeforeEach
    void setUp() {
        service = new CommissionAccrualService(shadowRepository, assignmentRepository, policyRepository,
                recordRepository, eventPublisher, new CommissionProperties());
        given(shadowRepository.findById(line)).willReturn(Optional.empty());
        given(shadowRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(assignmentRepository.findById(line)).willReturn(Optional.of(
                CreditPromoterAssignment.create(line, distributor, "DISTRIBUTOR_LINE")));
        given(recordRepository.save(any())).willAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        given(recordRepository.findByPaymentRef(any())).willAnswer(inv -> saved.stream()
                .filter(r -> inv.getArgument(0).equals(r.getPaymentRef())).toList());
    }

    private BalanceMove netting(String eventId, long daysLate, String rate, String amount) {
        return new BalanceMove(eventId, line, BalanceMove.COMMISSION_NETTED, 5L, new BigDecimal("20.00"),
                new BigDecimal("-180.00"), "COMMISSION_NETTED:pay-7:7", DUE.plusDays(daysLate), "NETTED",
                new Netting(7, DUE, daysLate, new BigDecimal(rate), new BigDecimal("0.18"), new BigDecimal("1000.00"),
                        new BigDecimal(amount), "pay-7"));
    }

    @Test
    @DisplayName("la comisión neteada se registra compensada, con su estado, su escalón y lo que su cliente pagó")
    void aNettedCommissionIsRecordedCompensated() {
        service.onBalanceUpdated(netting("evt-n1", 0, "0.18", "180.00"));

        assertThat(saved).hasSize(1);
        CommissionRecord r = saved.get(0);
        assertThat(r.getStatus()).isEqualTo(CommissionRecordStatus.COMPENSATED);
        assertThat(r.getSettlement()).isEqualTo("NETTED");
        assertThat(r.getBasis()).isEqualByComparingTo("1000.00");
        assertThat(r.getRate()).isEqualByComparingTo("0.18");
        assertThat(r.getAmount()).isEqualByComparingTo("180.00");
        assertThat(r.getIvaAmount()).isEqualByComparingTo("0");
        assertThat(r.getCycleNumber()).isEqualTo(7);
        assertThat(r.getDueDate()).isEqualTo(DUE);
        assertThat(r.getDaysLate()).isZero();
        assertThat(r.getPaymentRef()).isEqualTo("pay-7");
        assertThat(r.getBeneficiaryPartyId()).isEqualTo(distributor);
        assertThat(r.getPeriod()).isEqualTo("202603");
        then(eventPublisher).should().publishCommissionAccrued(r);
    }

    @Test
    @DisplayName("dos días tarde queda registrada al 11 %, y el pago de una línea neteada no devenga nada por su cuenta")
    void lateTierIsKept_andTheNettedLinesPaymentAccruesNothing() {
        service.onBalanceUpdated(netting("evt-n2", 2, "0.11", "110.00"));
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getDaysLate()).isEqualTo(2);
        assertThat(saved.get(0).getRate()).isEqualByComparingTo("0.11");

        service.onBalanceUpdated(new BalanceMove("evt-p2", line, BalanceMove.PAYMENT_APPLIED, 6L, BigDecimal.ZERO,
                new BigDecimal("-20.00"), "pay-7", DUE.plusDays(2), "NETTED", null));
        assertThat(saved).as("no share of the interest on top of the netting").hasSize(1);
    }

    @Test
    @DisplayName("una entrega repetida del mismo neteo no se registra dos veces")
    void idempotent() {
        given(recordRepository.existsBySourceEventId("evt-n3")).willReturn(true);
        service.onBalanceUpdated(netting("evt-n3", 0, "0.18", "180.00"));
        assertThat(saved).isEmpty();
        then(eventPublisher).should(never()).publishCommissionAccrued(any());
    }

    @Test
    @DisplayName("si el pago se devuelve, el neteo se revierte una sola vez; la restitución del interés no lo toca")
    void aReturnedPaymentUndoesTheNettingOnce() {
        service.onBalanceUpdated(netting("evt-n4", 0, "0.18", "180.00"));
        BalanceMove undo = new BalanceMove("evt-u4", line, BalanceMove.COMMISSION_NETTED_REVERSED, 8L, new BigDecimal("200.00"),
                new BigDecimal("180.00"), "COMMISSION_NETTED_REVERSED:COMMISSION_NETTED:pay-7:7", DUE.plusDays(10), null,
                new Netting(7, null, 0, null, null, null, new BigDecimal("-180.00"), "pay-7"));

        service.onBalanceUpdated(undo);
        assertThat(saved).hasSize(2);
        CommissionRecord reversal = saved.get(1);
        assertThat(reversal.getStatus()).isEqualTo(CommissionRecordStatus.COMPENSATED);
        assertThat(reversal.getAmount()).isEqualByComparingTo("-180.00");
        assertThat(reversal.getBasis()).isEqualByComparingTo("-1000.00");
        assertThat(reversal.getReversesCommissionId()).isEqualTo(saved.get(0).getCommissionId());
        assertThat(reversal.getCycleNumber()).isEqualTo(7);
        then(eventPublisher).should().publishCommissionReversed(reversal);

        // Delivered again under another event id: the netting is already undone.
        service.onBalanceUpdated(new BalanceMove("evt-u4b", line, BalanceMove.COMMISSION_NETTED_REVERSED, 9L, null,
                new BigDecimal("180.00"), "COMMISSION_NETTED_REVERSED:COMMISSION_NETTED:pay-7:7", DUE.plusDays(10), null,
                undo.commission()));
        assertThat(saved).hasSize(2);

        // The cash return itself restores interest: a netted commission is not a share of it, nothing more is reversed.
        service.onBalanceUpdated(new BalanceMove("evt-r4", line, BalanceMove.PAYMENT_RETURNED, 10L, new BigDecimal("220.00"),
                new BigDecimal("20.00"), "PAYMENT_RETURNED:pay-7", DUE.plusDays(10), "NETTED", null));
        assertThat(saved).hasSize(2);
    }

    @Test
    @DisplayName("sin promotora asignada no hay a quién registrarle la comisión")
    void withoutAnAssignmentNothingIsRecorded() {
        given(assignmentRepository.findById(line)).willReturn(Optional.empty());
        service.onBalanceUpdated(netting("evt-n5", 0, "0.18", "180.00"));
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("rendimiento: veinte mil neteos se registran en menos de un segundo")
    void twentyThousandNettingsInUnderASecond() {
        long start = System.nanoTime();
        for (int n = 0; n < 20_000; n++) {
            service.onBalanceUpdated(netting("evt-perf-" + n, n % 5, "0.18", "180.00"));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(saved).hasSize(20_000);
        assertThat(ms).as("20,000 nettings took %d ms", ms).isLessThan(1_000);
    }
}
