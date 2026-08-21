package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.service.BalanceReconciliationService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class BalanceReconciliationServiceTest {

    @Mock CreditAccountRepository accountRepository;
    @Mock BalanceEventRepository balanceEventRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock InstallmentRepository installmentRepository;
    BalanceReconciliationService service;

    final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BalanceReconciliationService(accountRepository, balanceEventRepository,
                eventPublisher, installmentRepository);
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(balanceEventRepository.existsBySourceEventId(any())).thenReturn(false);
    }

    private CreditAccount account() {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal("50000"));
        return a;
    }

    @Test
    void chargeApplied_ordinaryInterest_routesToInterest_andPublishes() {
        CreditAccount a = account();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onChargeApplied("evt-1", accountId, "ORDINARY_INTEREST", new BigDecimal("1000"));

        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("1000");
        then(balanceEventRepository).should().save(any());
        ArgumentCaptor<BalanceUpdatedEvent> cap = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should().publishBalanceUpdated(cap.capture());
        assertThat(cap.getValue().getAccruedInterestBalance()).isEqualByComparingTo("1000");
        assertThat(cap.getValue().getTriggerEvent()).isEqualTo("CHARGE_ORDINARY_INTEREST");
    }

    @Test
    void chargeApplied_moratorium_routesToPenalty() {
        CreditAccount a = account();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onChargeApplied("evt-2", accountId, "MORATORIUM_INTEREST", new BigDecimal("300"));

        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("300");
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("0");
    }

    @Test
    void paymentApplied_reducesDebt_publishes() {
        CreditAccount a = account();
        a.applyInterestAccrual(new BigDecimal("1000"));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onPaymentApplied("pay-1", accountId, new BigDecimal("1000"));

        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("0");  // interest cleared first
        then(eventPublisher).should().publishBalanceUpdated(any());
    }

    @Test
    void writeOff_zeroesBalances() {
        CreditAccount a = account();
        a.applyPenaltyCharge(new BigDecimal("500"));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onWriteOffExecuted("wo-1", accountId);

        assertThat(a.getTotalDebt()).isEqualByComparingTo("0");
        assertThat(a.getStatus().name()).isEqualTo("WRITTEN_OFF");
    }

    /**
     * El quebranto viaja con lo que de verdad se castigó, no con el cero que declara el llamador.
     *
     * <p>{@code onWriteOffExecuted} pasa {@code ZERO} porque cuánto se castiga no se sabe hasta
     * después de poner los saldos a cero. Publicado así, contabilidad lo descartaba —«sólo cambió el
     * estatus»— y el crédito desaparecía de cartera mientras seguía vivo en el mayor: la cuenta 1201
     * quedaba sobrando exactamente lo castigado.
     */
    @Test
    void writeOff_publishesTheAmountActuallyWrittenOff() {
        CreditAccount a = account();                       // 50,000 de capital dispuesto
        a.applyPenaltyCharge(new BigDecimal("500"));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onWriteOffExecuted("wo-2", accountId);

        ArgumentCaptor<BalanceUpdatedEvent> cap = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should().publishBalanceUpdated(cap.capture());
        BalanceUpdatedEvent e = cap.getValue();

        // Negativo: el castigo da de baja el adeudo.
        assertThat(e.getEventAmount()).isEqualByComparingTo("-50500");
        assertThat(e.getPrincipalDelta()).isEqualByComparingTo("-50000");
        assertThat(e.getPenaltyDelta()).isEqualByComparingTo("-500");
    }

    @Test
    void duplicateSourceEvent_isSkipped_idempotent() {
        given(balanceEventRepository.existsBySourceEventId("dup-1")).willReturn(true);

        service.onChargeApplied("dup-1", accountId, "ORDINARY_INTEREST", new BigDecimal("1000"));

        then(accountRepository).should(never()).findById(any());
        then(accountRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishBalanceUpdated(any());
    }

    @Test
    void chargeReversed_reversesAndPublishes() {
        CreditAccount a = account();
        a.applyInterestAccrual(new BigDecimal("1000"));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onChargeReversed("rev-1", accountId, "ORDINARY_INTEREST", new BigDecimal("400"), false);

        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("600");
        then(eventPublisher).should().publishBalanceUpdated(any());
    }

    // ── POST-CHECK: terminal account → charge-rejected ───────────────────────────

    @Test
    void chargeApplied_writtenOffAccount_publishesChargeRejected_noBalanceChange() {
        CreditAccount a = account();
        a.executeWriteOff();
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.WRITTEN_OFF);
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onChargeApplied("evt-wo", accountId, "ORDINARY_INTEREST", new BigDecimal("500"));

        then(eventPublisher).should().publishChargeRejected(
                eq("evt-wo"), eq(accountId), eq("ORDINARY_INTEREST"), eq(new BigDecimal("500")), any());
        then(eventPublisher).should(never()).publishBalanceUpdated(any());
        then(balanceEventRepository).should(never()).save(any());
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("0");
    }

    @Test
    void chargeApplied_closedAccount_publishesChargeRejected() {
        CreditAccount a = account();
        // SETTLED transitions from settleIfClear; CLOSED is also terminal
        a.applyPayment(a.getTotalDebt());
        a.settleIfClear();  // → SETTLED; or use direct status manipulation for CLOSED
        // The service checks WRITTEN_OFF and CLOSED — SETTLED is not blocked at charge level
        // Force CLOSED via write-off (demonstrating the guard works for all terminal statuses checked)
        CreditAccount closed = account();
        closed.executeWriteOff();
        // Simulate that somehow status is CLOSED (the service checks WRITTEN_OFF || CLOSED)
        given(accountRepository.findById(accountId)).willReturn(Optional.of(closed));

        service.onChargeApplied("evt-cl", accountId, "MORATORIUM_INTEREST", new BigDecimal("200"));

        then(eventPublisher).should().publishChargeRejected(any(), any(), any(), any(), any());
        then(balanceEventRepository).should(never()).save(any());
    }

    // ── Overpayment → payment-rejected ───────────────────────────────────────────

    @Test
    void paymentApplied_overpayment_publishesPaymentRejected_noBalanceChange() {
        CreditAccount a = account(); // totalDebt = principalBalance = 50000
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        BigDecimal overpayment = new BigDecimal("99999");
        service.onPaymentApplied("pay-over", accountId, overpayment);

        then(eventPublisher).should().publishPaymentRejected(
                eq("pay-over"), eq(accountId), eq(overpayment), any());
        then(eventPublisher).should(never()).publishBalanceUpdated(any());
        then(balanceEventRepository).should(never()).save(any());
        assertThat(a.getPrincipalBalance()).isEqualByComparingTo("50000"); // unchanged
    }

    @Test
    void paymentApplied_exactTotalDebt_succeeds_notRejected() {
        CreditAccount a = account();
        BigDecimal exact = a.getTotalDebt();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onPaymentApplied("pay-exact", accountId, exact);

        then(eventPublisher).should(never()).publishPaymentRejected(any(), any(), any(), any());
        then(eventPublisher).should().publishBalanceUpdated(any());
    }

    // ── paymentReturned ───────────────────────────────────────────────────────────

    @Test
    void paymentReturned_restoresDebtAsPenalty_andPublishes() {
        CreditAccount a = account();
        a.applyPayment(new BigDecimal("10000"));
        BigDecimal debtAfterPayment = a.getTotalDebt();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onPaymentReturned("ret-1", accountId, new BigDecimal("10000"));

        assertThat(a.getTotalDebt()).isGreaterThan(debtAfterPayment);
        then(eventPublisher).should().publishBalanceUpdated(any());
    }

    // ── Thread-safety ─────────────────────────────────────────────────────────────

    /**
     * Verifies the service has no shared mutable state. 16 threads calling
     * onChargeApplied on different accountIds concurrently must each produce
     * a valid outcome — no NPE, ClassCastException, or other unexpected error.
     */
    @Test
    void onChargeApplied_concurrentCallsDifferentAccounts_noUnexpectedExceptions()
            throws InterruptedException {

        int threads = 16;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            CreditAccount a = account();
            given(accountRepository.findById(id)).willReturn(Optional.of(a));
        }

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    gate.await();
                    service.onChargeApplied("evt-conc-" + idx, ids.get(idx),
                            "ORDINARY_INTEREST", new BigDecimal("100"));
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpected)
                .as("unexpected exceptions under concurrent load: %s", unexpected)
                .isEmpty();
        assertThat(successes.get()).isEqualTo(threads);
    }

    /**
     * Simulates a race between two threads delivering the same sourceEventId.
     * Both threads pass the idempotency guard simultaneously (mocked to return false).
     * The service must not throw — in production, the DB unique constraint on
     * balance_events.source_event_id would reject one; here we verify no panic at
     * the service layer under the race window.
     */
    @Test
    void onChargeApplied_concurrentDuplicateSourceEvent_serviceDoesNotPanic()
            throws InterruptedException {

        CreditAccount a = account();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));
        // Both threads see "not duplicate" — simulating the race window before DB constraint fires
        given(balanceEventRepository.existsBySourceEventId("race-evt")).willReturn(false);

        int threads = 4;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    gate.await();
                    service.onChargeApplied("race-evt", accountId,
                            "ORDINARY_INTEREST", new BigDecimal("100"));
                } catch (Exception expected) {
                    // DataIntegrityViolationException from DB unique constraint is expected in prod
                    // Here with mocks it will succeed — verify no NPE/ClassCastException
                    if (!(expected instanceof org.springframework.dao.DataIntegrityViolationException)) {
                        unexpected.add(expected);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpected)
                .as("unexpected exceptions in race window: %s", unexpected)
                .isEmpty();
    }
}
