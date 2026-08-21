package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
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

    @Mock CreditAccountRepository accountRepository;
    @Mock InstallmentRepository installmentRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock DispositionRepository dispositionRepository;

    DelinquencyAccountProcessor processor;
    DelinquencyCalculationJob job;

    @BeforeEach
    void setUp() {
        processor = new DelinquencyAccountProcessor(accountRepository, installmentRepository, dispositionRepository, eventPublisher);
        job = new DelinquencyCalculationJob(accountRepository, processor);
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
        LocalDate today = LocalDate.now();
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
        LocalDate today = LocalDate.now();
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
        LocalDate today = LocalDate.now();
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
        LocalDate today = LocalDate.now();
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
