package com.fintech.payments.application.service;

import com.fintech.payments.application.PaymentsProperties;
import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.PaymentEventPublisher;
import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PaymentService.
 *
 * Pre-existing cases (PY-01, PY-02, PY-03 pre-check):
 *   PY-T01  valid submit → PENDING + payment-applied event
 *   PY-T02  amount > totalDebt (legacy pre-reject) — NOW passes (overpayment handling)
 *   PY-T03  idempotency — same externalRef returns existing order
 *   PY-T04  no snapshot → InvalidPaymentStateException
 *   PY-T05  reject by eventId
 *   PY-T06  reverse CONFIRMED → REVERSED + payment-returned event
 *
 * New cases (PY-05 to PY-08):
 *   PY-T07  WRITTEN_OFF account → InvalidPaymentStateException
 *   PY-T08  CLOSED account → InvalidPaymentStateException
 *   PY-T09  overpayment + RETURN_TO_PAYER → applies totalDebt; publishes excess payment-returned
 *   PY-T10  overpayment + APPLY_NEXT_INSTALLMENT → forwards full amount; no immediate return
 *   PY-T11  reversal window expired → InvalidPaymentStateException
 *   PY-T12  VENTANILLA payment → cannot be reversed
 *   PY-T13  overpayment excess recorded on PaymentOrder
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentOrderRepository orderRepo;
    @Mock AccountBalanceSnapshotRepository snapshotRepo;
    @Mock PaymentEventPublisher eventPublisher;

    PaymentsProperties properties;
    PaymentService service;

    @BeforeEach
    void setUp() {
        properties = new PaymentsProperties();
        service = new PaymentService(orderRepo, snapshotRepo, eventPublisher, properties);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    AccountBalanceSnapshot buildSnapshot(UUID accountId, BigDecimal totalDebt, long version, String status) {
        AccountBalanceSnapshot s = AccountBalanceSnapshot.init(accountId, UUID.randomUUID(), BigDecimal.ZERO);
        s.update(totalDebt, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, totalDebt, version, status);
        return s;
    }

    AccountBalanceSnapshot activeSnapshot(UUID accountId, BigDecimal totalDebt) {
        return buildSnapshot(accountId, totalDebt, 3L, "ACTIVE");
    }

    // ── PY-T01 ──────────────────────────────────────────────────────────────────

    @Test
    void pyT01_submit_preValidates_andPublishesWhenValid() {
        UUID accountId = UUID.randomUUID();
        BigDecimal totalDebt = new BigDecimal("5000.00");
        BigDecimal payment   = new BigDecimal("1000.00");
        AccountBalanceSnapshot snapshot = activeSnapshot(accountId, totalDebt);

        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));
        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder order = service.submit(accountId, UUID.randomUUID(), payment,
                PaymentMethod.SPEI, "CR-2026-001");

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING.name());
        assertThat(order.getSnapshotVersion()).isEqualTo(3L);
        verify(eventPublisher).publishPaymentApplied(any(), eq(accountId), eq(payment), eq(3L));
    }

    // ── PY-T02 (was pre-reject, now overpayment) ────────────────────────────────

    @Test
    void pyT02_submit_overpayment_defaultStrategy_returnsExcess() {
        UUID accountId = UUID.randomUUID();
        BigDecimal totalDebt = new BigDecimal("500.00");
        BigDecimal payment   = new BigDecimal("1000.00");
        AccountBalanceSnapshot snapshot = activeSnapshot(accountId, totalDebt);

        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));
        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder order = service.submit(accountId, UUID.randomUUID(), payment,
                PaymentMethod.SPEI, "CR-2026-002");

        // Applies only totalDebt
        assertThat(order.getAmount()).isEqualByComparingTo("500.00");
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING.name());

        // payment-applied published for totalDebt
        verify(eventPublisher).publishPaymentApplied(any(), eq(accountId), eq(totalDebt), eq(3L));
        // payment-returned published for excess (500)
        verify(eventPublisher).publishPaymentReversed(contains("-excess"), eq(accountId),
                eq(new BigDecimal("500.00")), eq("OVERPAYMENT_RETURN_TO_PAYER"));
    }

    // ── PY-T03 ──────────────────────────────────────────────────────────────────

    @Test
    void pyT03_submit_isIdempotent_whenSameExternalRef() {
        UUID accountId   = UUID.randomUUID();
        PaymentOrder existing = PaymentOrder.create(accountId, UUID.randomUUID(),
                new BigDecimal("1000"), PaymentMethod.SPEI, "CR-DUP-001", 1L);

        when(orderRepo.existsByExternalRef("CR-DUP-001")).thenReturn(true);
        when(orderRepo.findByExternalRef("CR-DUP-001")).thenReturn(Optional.of(existing));

        PaymentOrder result = service.submit(accountId, UUID.randomUUID(), new BigDecimal("1000"),
                PaymentMethod.SPEI, "CR-DUP-001");

        assertThat(result.getExternalRef()).isEqualTo("CR-DUP-001");
        verifyNoInteractions(snapshotRepo, eventPublisher);
    }

    // ── PY-T04 ──────────────────────────────────────────────────────────────────

    @Test
    void pyT04_submit_throws_whenNoSnapshot() {
        UUID accountId = UUID.randomUUID();
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.empty());
        when(orderRepo.existsByExternalRef(any())).thenReturn(false);

        assertThatThrownBy(() -> service.submit(accountId, UUID.randomUUID(),
                new BigDecimal("100"), PaymentMethod.SPEI, "REF-001"))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("No balance snapshot");
    }

    // ── PY-T05 ──────────────────────────────────────────────────────────────────

    @Test
    void pyT05_rejectByEventId_transitionsToRejected() {
        UUID orderId   = UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("1000"), PaymentMethod.SPEI, "REF-X", 1L);

        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rejectByEventId(orderId.toString(), "OVERPAYMENT");

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.REJECTED.name());
    }

    // ── PY-T06 ──────────────────────────────────────────────────────────────────

    @Test
    void pyT06_reverse_transitionsToReversedAndPublishes() {
        UUID orderId   = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(accountId, UUID.randomUUID(),
                new BigDecimal("500"), PaymentMethod.SPEI, "REF-Z", 2L);
        order.confirm();

        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder reversed = service.reverse(orderId, "SPEI devolution");

        assertThat(reversed.getStatus()).isEqualTo(PaymentStatus.REVERSED.name());
        verify(eventPublisher).publishPaymentReversed(any(), eq(accountId),
                eq(new BigDecimal("500")), eq("SPEI devolution"));
    }

    // ── PY-T07: WRITTEN_OFF guard ─────────────────────────────────────────────

    @Test
    void pyT07_submit_writtenOffAccount_throws() {
        UUID accountId = UUID.randomUUID();
        AccountBalanceSnapshot snapshot = buildSnapshot(accountId, BigDecimal.ZERO, 1L, "WRITTEN_OFF");

        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.submit(accountId, UUID.randomUUID(),
                new BigDecimal("100"), PaymentMethod.SPEI, "REF-WO"))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("WRITTEN_OFF");

        verifyNoInteractions(eventPublisher);
    }

    // ── PY-T08: CLOSED guard ──────────────────────────────────────────────────

    @Test
    void pyT08_submit_closedAccount_throws() {
        UUID accountId = UUID.randomUUID();
        AccountBalanceSnapshot snapshot = buildSnapshot(accountId, BigDecimal.ZERO, 1L, "CLOSED");

        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));

        assertThatThrownBy(() -> service.submit(accountId, UUID.randomUUID(),
                new BigDecimal("100"), PaymentMethod.SPEI, "REF-CL"))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("CLOSED");

        verifyNoInteractions(eventPublisher);
    }

    // ── PY-T09: overpayment RETURN_TO_PAYER (explicit config) ─────────────────

    @Test
    void pyT09_overpayment_returnToPayer_appliesDebtAndPublishesExcess() {
        properties.setOverpaymentStrategy(OverpaymentStrategy.RETURN_TO_PAYER);
        UUID accountId = UUID.randomUUID();
        BigDecimal totalDebt = new BigDecimal("800.00");
        BigDecimal payment   = new BigDecimal("1000.00");
        AccountBalanceSnapshot snapshot = activeSnapshot(accountId, totalDebt);

        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder order = service.submit(accountId, UUID.randomUUID(), payment,
                PaymentMethod.CODI, "CODI-REF-01");

        // Applied amount = totalDebt
        assertThat(order.getAmount()).isEqualByComparingTo("800.00");
        // Original customer amount recorded
        assertThat(order.getRequestedAmount()).isEqualByComparingTo("1000.00");
        assertThat(order.getExcessAmount()).isEqualByComparingTo("200.00");
        assertThat(order.getOverpaymentStrategy()).isEqualTo("RETURN_TO_PAYER");

        // payment-applied for 800 (applied)
        verify(eventPublisher).publishPaymentApplied(any(), eq(accountId),
                eq(new BigDecimal("800.00")), eq(3L));
        // payment-returned for 200 (excess)
        ArgumentCaptor<BigDecimal> excessCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(eventPublisher).publishPaymentReversed(any(), eq(accountId),
                excessCaptor.capture(), eq("OVERPAYMENT_RETURN_TO_PAYER"));
        assertThat(excessCaptor.getValue()).isEqualByComparingTo("200.00");
    }

    // ── PY-T10: overpayment APPLY_NEXT_INSTALLMENT ───────────────────────────

    @Test
    void pyT10_overpayment_applyNextInstallment_forwardsFullAmount() {
        properties.setOverpaymentStrategy(OverpaymentStrategy.APPLY_NEXT_INSTALLMENT);
        UUID accountId = UUID.randomUUID();
        BigDecimal totalDebt = new BigDecimal("800.00");
        BigDecimal payment   = new BigDecimal("1200.00");
        AccountBalanceSnapshot snapshot = activeSnapshot(accountId, totalDebt);

        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder order = service.submit(accountId, UUID.randomUUID(), payment,
                PaymentMethod.DOMICILIACION, "DOM-REF-01");

        // Full amount forwarded to credit-portfolio
        assertThat(order.getAmount()).isEqualByComparingTo("1200.00");
        assertThat(order.getOverpaymentStrategy()).isEqualTo("APPLY_NEXT_INSTALLMENT");

        verify(eventPublisher).publishPaymentApplied(any(), eq(accountId),
                eq(new BigDecimal("1200.00")), eq(3L));
        // No excess return event
        verify(eventPublisher, never()).publishPaymentReversed(contains("-excess"), any(), any(), any());
    }

    // ── PY-T11: reversal window expired ──────────────────────────────────────

    @Test
    void pyT11_reverse_afterWindowExpired_throws() {
        properties.setReversalWindowHours(72);
        UUID orderId   = UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("500"), PaymentMethod.SPEI, "REF-OLD", 1L);
        order.confirm();

        // Simulate confirmedAt 73 hours ago by manipulating via reflection... or just set window to 0
        // Use a 0-hour window so any confirmed order is "expired"
        properties.setReversalWindowHours(0);

        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.reverse(orderId, "too late"))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("window expired");

        verifyNoInteractions(eventPublisher);
    }

    // ── PY-T12: VENTANILLA cannot be reversed ────────────────────────────────

    @Test
    void pyT12_reverse_ventanillaPayment_throws() {
        UUID orderId   = UUID.randomUUID();
        PaymentOrder order = PaymentOrder.create(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("200"), PaymentMethod.VENTANILLA, "FOLI-001", 1L);
        order.confirm();

        when(orderRepo.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.reverse(orderId, "return cash"))
                .isInstanceOf(InvalidPaymentStateException.class)
                .hasMessageContaining("VENTANILLA");

        verifyNoInteractions(eventPublisher);
    }

    // ── PY-T13: excess recorded correctly ────────────────────────────────────

    @Test
    void pyT13_overpayment_excessAmountIsCorrect() {
        UUID accountId = UUID.randomUUID();
        BigDecimal totalDebt = new BigDecimal("1000.00");
        BigDecimal payment   = new BigDecimal("1500.00");
        AccountBalanceSnapshot snapshot = activeSnapshot(accountId, totalDebt);

        when(orderRepo.existsByExternalRef(any())).thenReturn(false);
        when(snapshotRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));
        when(orderRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentOrder order = service.submit(accountId, UUID.randomUUID(), payment,
                PaymentMethod.SPEI, "REF-EXCESS");

        assertThat(order.getExcessAmount()).isEqualByComparingTo("500.00");
        assertThat(order.getRequestedAmount()).isEqualByComparingTo("1500.00");
        assertThat(order.getAmount()).isEqualByComparingTo("1000.00");
    }
}
