package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.infrastructure.job.DelinquencyAccountProcessor;
import com.fintech.creditportfolio.infrastructure.job.DelinquencyCalculationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DelinquencyCalculationJob and DelinquencyAccountProcessor.
 *
 * DQ-01  No ACTIVE accounts → job completes without any DB writes or events
 * DQ-02  ACTIVE account, no overdue installments → daysDelinquent = 0, event published
 * DQ-03  ACTIVE account with overdue installments → daysDelinquent = days since earliest
 * DQ-04  Multiple overdue installments → earliest due date drives the calculation
 * DQ-05  Processor exception on one account → job continues processing remaining accounts
 */
@ExtendWith(MockitoExtension.class)
class DelinquencyCalculationJobTest {

    /** Reloj fijo en la zona de negocio: la prueba no depende de la hora ni de la zona de la máquina. */
    private static final com.fintech.shared.time.BusinessClock CLOCK = new com.fintech.shared.time.BusinessClock(
            java.time.Clock.fixed(java.time.Instant.parse("2026-10-30T18:00:00Z"),
                    java.time.ZoneId.of("America/Mexico_City")));

    @Mock CreditAccountRepository accountRepository;
    @Mock InstallmentRepository installmentRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock DispositionRepository dispositionRepository;

    DelinquencyAccountProcessor processor;
    DelinquencyCalculationJob job;

    @BeforeEach
    void setUp() {
        processor = new DelinquencyAccountProcessor(accountRepository, installmentRepository, dispositionRepository, eventPublisher);
        job = new DelinquencyCalculationJob(accountRepository, processor, CLOCK);
    }

    // ── DQ-01 ──────────────────────────────────────────────────────────────────

    @Test
    void dq01_noActiveAccounts_nothingProcessed() {
        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of());

        job.run();

        verify(installmentRepository, never()).findPendingOverdueByScheduleId(any(), any());
        verify(eventPublisher, never()).publishDelinquencyStatusUpdated(any());
    }

    // ── DQ-02 ──────────────────────────────────────────────────────────────────

    @Test
    void dq02_activeAccountNoOverdue_setsZeroDaysAndPublishes() {
        LocalDate today = CLOCK.today();
        CreditAccount account = activeAccount("50000");
        UUID accountId = account.getCreditAccountId();

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(account));
        given(installmentRepository.findPendingOverdueByScheduleId(accountId, today)).willReturn(List.of());
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        job.run();

        assertThat(account.getDaysDelinquent()).isZero();

        ArgumentCaptor<DelinquencyStatusUpdatedEvent> captor =
                ArgumentCaptor.forClass(DelinquencyStatusUpdatedEvent.class);
        verify(eventPublisher).publishDelinquencyStatusUpdated(captor.capture());
        assertThat(captor.getValue().getCreditAccountId()).isEqualTo(accountId);
        assertThat(captor.getValue().getDaysDelinquent()).isZero();
    }

    // ── DQ-03 ──────────────────────────────────────────────────────────────────

    @Test
    void dq03_singleOverdueInstallment_calculatesDaysCorrectly() {
        LocalDate today = CLOCK.today();
        LocalDate dueDate = today.minusDays(10);
        CreditAccount account = activeAccount("50000");
        UUID accountId = account.getCreditAccountId();
        Installment overdue = installment(accountId, 1, dueDate);

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(account));
        given(installmentRepository.findPendingOverdueByScheduleId(accountId, today)).willReturn(List.of(overdue));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        job.run();

        assertThat(account.getDaysDelinquent()).isEqualTo(10);

        ArgumentCaptor<DelinquencyStatusUpdatedEvent> captor =
                ArgumentCaptor.forClass(DelinquencyStatusUpdatedEvent.class);
        verify(eventPublisher).publishDelinquencyStatusUpdated(captor.capture());
        assertThat(captor.getValue().getDaysDelinquent()).isEqualTo(10);
    }

    // ── DQ-04 ──────────────────────────────────────────────────────────────────

    @Test
    void dq04_multipleOverdueInstallments_usesEarliestDueDate() {
        LocalDate today = CLOCK.today();
        CreditAccount account = activeAccount("50000");
        UUID accountId = account.getCreditAccountId();

        Installment inst1 = installment(accountId, 1, today.minusDays(30));  // earliest
        Installment inst2 = installment(accountId, 2, today.minusDays(15));
        Installment inst3 = installment(accountId, 3, today.minusDays(5));

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(account));
        given(installmentRepository.findPendingOverdueByScheduleId(accountId, today))
                .willReturn(List.of(inst3, inst1, inst2));  // unsorted on purpose
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        job.run();

        assertThat(account.getDaysDelinquent()).isEqualTo(30);
    }

    // ── DQ-05 ──────────────────────────────────────────────────────────────────

    @Test
    void dq05_processorThrowsForOneAccount_continuesWithRemainingAccounts() {
        LocalDate today = CLOCK.today();
        CreditAccount failing  = activeAccount("10000");
        CreditAccount succeeding = activeAccount("20000");

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE))
                .willReturn(List.of(failing, succeeding));

        // First account: repository throws
        given(installmentRepository.findPendingOverdueByScheduleId(failing.getCreditAccountId(), today))
                .willThrow(new RuntimeException("DB error"));
        // Second account: OK
        given(installmentRepository.findPendingOverdueByScheduleId(succeeding.getCreditAccountId(), today))
                .willReturn(List.of());
        given(accountRepository.save(succeeding)).willReturn(succeeding);

        job.run();   // must not throw

        verify(eventPublisher, times(1)).publishDelinquencyStatusUpdated(any());
    }

    // ── DQ-06: delinquency on principal ───────────────────────────────────────

    @Test
    void dq06_principalAlreadyPaid_isCurrentEvenWithAPartialInstallment() {
        LocalDate today = CLOCK.today();
        CreditAccount account = activeAccount("500");
        UUID accountId = account.getCreditAccountId();
        Installment partial = installment(accountId, 1, today.minusDays(34));
        Installment next = installment(accountId, 2, today.plusDays(10));

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(account));
        given(installmentRepository.findPendingOverdueByScheduleId(accountId, today)).willReturn(List.of(partial));
        given(installmentRepository.findByScheduleId(accountId)).willReturn(List.of(partial, next));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        job.run();

        // 500 owed, all of it covered by the installment not due yet: nothing is overdue.
        assertThat(account.getDaysDelinquent()).isZero();
        ArgumentCaptor<DelinquencyStatusUpdatedEvent> captor = ArgumentCaptor.forClass(DelinquencyStatusUpdatedEvent.class);
        verify(eventPublisher).publishDelinquencyStatusUpdated(captor.capture());
        assertThat(captor.getValue().getOverduePrincipal()).isEqualByComparingTo("0");
    }

    @Test
    void dq07_remainingOverduePrincipalBelongsToTheNewestInstallments() {
        LocalDate today = CLOCK.today();
        CreditAccount account = activeAccount("6000");
        UUID accountId = account.getCreditAccountId();
        Installment oldest = installment(accountId, 1, today.minusDays(35));
        Installment newest = installment(accountId, 2, today.minusDays(5));
        Installment future = installment(accountId, 3, today.plusDays(25));

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(account));
        given(installmentRepository.findPendingOverdueByScheduleId(accountId, today)).willReturn(List.of(oldest, newest));
        given(installmentRepository.findByScheduleId(accountId)).willReturn(List.of(oldest, newest, future));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        job.run();

        // 6,000 owed minus the future installment's 4,000 leaves 2,000 overdue: part of the newest installment.
        assertThat(account.getDaysDelinquent()).isEqualTo(5);
    }

    // ── DQ-08: statement-billed products are late on their statements ────────

    @Test
    void dq08_statementBilledProductIsLateOnlyOnWhatItsStatementsAskedAndWasNotPaid() {
        LocalDate today = CLOCK.today();
        BalanceEventRepository ledger = mock(BalanceEventRepository.class);
        DelinquencyAccountProcessor byStatement = new DelinquencyAccountProcessor(
                accountRepository, installmentRepository, dispositionRepository, eventPublisher, ledger);
        DelinquencyCalculationJob statementJob = new DelinquencyCalculationJob(accountRepository, byStatement, CLOCK);
        CreditAccount line = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-LINEA", UUID.randomUUID(), "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("500000"), null, new BigDecimal("0.20"), new BigDecimal("0.30"),
                null, new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        line.activate(BigDecimal.ZERO);
        line.applyDisposition(new BigDecimal("30000"));
        UUID id = line.getCreditAccountId();
        // Statements asked 6,000 (20 days ago) and 6,500 (6 days ago); 9,000 was paid.
        Installment first = Installment.of(id, 1, today.minusDays(20), new BigDecimal("6000"), BigDecimal.ZERO);
        Installment second = Installment.of(id, 2, today.minusDays(6), new BigDecimal("6500"), BigDecimal.ZERO);

        given(accountRepository.findAllByStatus(CreditAccountStatus.ACTIVE)).willReturn(List.of(line));
        given(installmentRepository.findByScheduleId(id)).willReturn(List.of(first, second));
        given(ledger.paymentsOf(id)).willReturn(List.of(BalanceEvent.record(id, "pago-1", "PAYMENT_APPLIED",
                new BigDecimal("-9000"), line, today.minusDays(10))));
        given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        statementJob.run();

        assertThat(line.getDaysDelinquent()).isEqualTo(6);
        ArgumentCaptor<DelinquencyStatusUpdatedEvent> captor = ArgumentCaptor.forClass(DelinquencyStatusUpdatedEvent.class);
        verify(eventPublisher).publishDelinquencyStatusUpdated(captor.capture());
        assertThat(captor.getValue().getOverduePrincipal()).isEqualByComparingTo("3500");

        // Paying the rest makes it current, whatever its plans' installments say.
        reset(eventPublisher);
        given(ledger.paymentsOf(id)).willReturn(List.of(
                BalanceEvent.record(id, "pago-1", "PAYMENT_APPLIED", new BigDecimal("-9000"), line, today.minusDays(10)),
                BalanceEvent.record(id, "pago-2", "PAYMENT_APPLIED", new BigDecimal("-3500"), line, today.minusDays(1))));
        statementJob.run();
        assertThat(line.getDaysDelinquent()).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CreditAccount activeAccount(String principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-" + principal, UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal(principal), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", new BigDecimal("1.0"), "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal(principal));
        return a;
    }

    private Installment installment(UUID scheduleId, int number, LocalDate dueDate) {
        return Installment.of(scheduleId, number, dueDate,
                new BigDecimal("4000.00"), new BigDecimal("100.00"));
    }
}
