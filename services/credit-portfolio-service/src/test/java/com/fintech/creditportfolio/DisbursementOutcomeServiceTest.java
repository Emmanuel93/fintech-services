package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.in.SettleDisbursementUseCase.DisbursementFailure;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.DisbursementOutcomeService;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionStatus;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountCancelledEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionFailedEvent;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * The saga of a disposition's payment (D63): retried while another attempt may overcome the failure; compensated at
 * once when it cannot happen or comes back. A cancelled installment credit owes nothing, its plan is cancelled, and
 * the services that booked something for it hear it.
 */
class DisbursementOutcomeServiceTest {

    private CreditAccountRepository accounts;
    private DispositionRepository dispositions;
    private InstallmentRepository installments;
    private BalanceEventRepository balanceEvents;
    private CreditPortfolioEventPublisher publisher;
    private DisbursementOutcomeService saga;

    @BeforeEach
    void setUp() {
        accounts = mock(CreditAccountRepository.class);
        dispositions = mock(DispositionRepository.class);
        installments = mock(InstallmentRepository.class);
        balanceEvents = mock(BalanceEventRepository.class);
        publisher = mock(CreditPortfolioEventPublisher.class);
        saga = new DisbursementOutcomeService(accounts, dispositions, installments, balanceEvents, publisher,
                3, "PT1H,PT6H");
        given(dispositions.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(accounts.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(accounts.lockAndLoad(any())).willReturn(Optional.empty());
    }

    private CreditAccount personalLoan() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-PL-D63", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.03"), "032180000118359719", "BAJO", null, null);
        account.activate(new BigDecimal("50000"));
        given(accounts.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        return account;
    }

    private CreditAccount revolvingLine() {
        CreditAccount account = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-REV-D63", UUID.randomUUID(),
                "RL-001", 1, "REVOLVING_LINE", "REVOLVING",
                null, new BigDecimal("20000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.02"), "032180000118359719", "MEDIO", null, null);
        account.activate(BigDecimal.ZERO);
        account.applyDisposition(new BigDecimal("5000"));
        given(accounts.findById(account.getCreditAccountId())).willReturn(Optional.of(account));
        return account;
    }

    private Disposition inFlight(CreditAccount account, String amount, boolean withPayee) {
        Disposition d = Disposition.create(account.getCreditAccountId(), DispositionType.SELF_USE,
                new BigDecimal(amount), null);
        d.markProcessing();
        if (withPayee) {
            d.recordPayee("JUANA PEREZ GOMEZ", "032180000118359719", "40", "PEGJ800101AB1", "DISPOSICION DE CREDITO");
        }
        given(dispositions.findById(d.getDispositionId())).willReturn(Optional.of(d));
        return d;
    }

    private List<Installment> plan(UUID scheduleId, int n) {
        List<Installment> plan = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            plan.add(Installment.of(scheduleId, i, LocalDate.of(2026, 4, 1).plusMonths(i - 1),
                    new BigDecimal("4000.00"), new BigDecimal("1000.00")));
        }
        given(installments.findByScheduleId(scheduleId)).willReturn(plan);
        return plan;
    }

    @Test
    @DisplayName("una falla técnica pide el siguiente intento, con la misma instrucción y su espera; nada se compensa")
    void technicalFailureAsksTheNextAttempt() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 1, "PROVIDER_TECHNICAL_FAILURE",
                "NO_REGISTRADA", true));

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.PROCESSING);
        assertThat(d.getAttempt()).isEqualTo(2);
        assertThat(d.getFailureCode()).isEqualTo("PROVIDER_TECHNICAL_FAILURE");
        ArgumentCaptor<DispositionAuthorizedEvent> asked = ArgumentCaptor.forClass(DispositionAuthorizedEvent.class);
        then(publisher).should().publishDispositionAuthorized(asked.capture());
        assertThat(asked.getValue().attempt()).isEqualTo(2);
        assertThat(asked.getValue().beneficiaryAccount()).isEqualTo("032180000118359719");
        assertThat(asked.getValue().beneficiaryName()).isEqualTo("JUANA PEREZ GOMEZ");
        assertThat(Duration.between(BusinessClock.current().now(), asked.getValue().notBefore()))
                .as("el segundo intento espera una hora").isBetween(Duration.ofMinutes(59), Duration.ofMinutes(61));
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("50000");
        then(balanceEvents).should(never()).save(any());
        then(publisher).should(never()).publishCreditAccountCancelled(any());
    }

    @Test
    @DisplayName("una regla del banco cancela el préstamo: capital de vuelta, calendario cancelado y cada servicio avisado")
    void ruleCancelsTheLoan() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);
        List<Installment> plan = plan(account.getCreditAccountId(), 12);

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 1, "REJECTED_BY_PROVIDER",
                "-200 Se rechaza por PLD", false));

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(d.getFailureCode()).isEqualTo("REJECTED_BY_PROVIDER");
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.CANCELLED);
        assertThat(account.getCancellationReason()).isEqualTo("DISBURSEMENT_FAILED");
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
        assertThat(plan).allMatch(i -> i.getStatus() == InstallmentStatus.CANCELLED);
        then(installments).should().saveAll(plan);

        ArgumentCaptor<BalanceEvent> moved = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEvents).should().save(moved.capture());
        assertThat(moved.getValue().getSourceEventId()).isEqualTo("DISPOSITION_FAILED:" + d.getDispositionId());
        assertThat(moved.getValue().getPrincipalDelta()).isEqualByComparingTo("-50000");

        ArgumentCaptor<BalanceUpdatedEvent> updated = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(publisher).should().publishBalanceUpdated(updated.capture());
        assertThat(updated.getValue().getAccountStatus()).isEqualTo("CANCELLED");
        assertThat(updated.getValue().getEventAmount()).isEqualByComparingTo("-50000");
        assertThat(updated.getValue().getPrincipalDelta()).isEqualByComparingTo("-50000");
        assertThat(updated.getValue().getSourceEventId()).isEqualTo("DISPOSITION_FAILED:" + d.getDispositionId());

        ArgumentCaptor<DispositionFailedEvent> failed = ArgumentCaptor.forClass(DispositionFailedEvent.class);
        then(publisher).should().publishDispositionFailed(failed.capture());
        assertThat(failed.getValue().accountCancelled()).isTrue();
        assertThat(failed.getValue().outcome()).isEqualTo("FAILED");
        ArgumentCaptor<CreditAccountCancelledEvent> cancelled = ArgumentCaptor.forClass(CreditAccountCancelledEvent.class);
        then(publisher).should().publishCreditAccountCancelled(cancelled.capture());
        assertThat(cancelled.getValue().contractId()).isEqualTo(account.getContractId());
        assertThat(cancelled.getValue().amount()).isEqualByComparingTo("50000");
        assertThat(cancelled.getValue().reason()).isEqualTo("DISBURSEMENT_FAILED");
        then(publisher).should(never()).publishDispositionAuthorized(any());
    }

    @Test
    @DisplayName("el tercer intento espera seis horas desde la falla del segundo")
    void theThirdAttemptWaitsSixHours() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);
        d.retry("PROVIDER_TECHNICAL_FAILURE", "NO_REGISTRADA");   // the first attempt failed: the second is in flight

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 2, "PROVIDER_TECHNICAL_FAILURE",
                "-30 ENLACE_FINANCIERO_MODO_CONSULTAS", true));

        assertThat(d.getAttempt()).isEqualTo(3);
        ArgumentCaptor<DispositionAuthorizedEvent> asked = ArgumentCaptor.forClass(DispositionAuthorizedEvent.class);
        then(publisher).should().publishDispositionAuthorized(asked.capture());
        assertThat(asked.getValue().attempt()).isEqualTo(3);
        assertThat(Duration.between(BusinessClock.current().now(), asked.getValue().notBefore()))
                .isBetween(Duration.ofMinutes(359), Duration.ofMinutes(361));
        then(publisher).should(never()).publishCreditAccountCancelled(any());
    }

    @Test
    @DisplayName("agotados los intentos, la falla técnica también se compensa")
    void attemptsUsedUpAreCompensated() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);
        plan(account.getCreditAccountId(), 3);
        d.retry("PROVIDER_TECHNICAL_FAILURE", "-30");
        d.retry("PROVIDER_TECHNICAL_FAILURE", "-30");

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 3, "PROVIDER_TECHNICAL_FAILURE",
                "-30 Enlace en modo consultas", true));

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.CANCELLED);
        then(publisher).should(never()).publishDispositionAuthorized(any());
        ArgumentCaptor<DispositionFailedEvent> failed = ArgumentCaptor.forClass(DispositionFailedEvent.class);
        then(publisher).should().publishDispositionFailed(failed.capture());
        assertThat(failed.getValue().attempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("el desenlace de un intento viejo no dice nada del pago en vuelo")
    void staleAttemptIsIgnored() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);
        d.retry("PROVIDER_TECHNICAL_FAILURE", "NO_REGISTRADA");

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 1, "REJECTED_BY_PROVIDER", "x", false));

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.PROCESSING);
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
        then(publisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("sin la instrucción guardada no hay cómo reintentar: se compensa")
    void withoutTheInstructionItIsCompensated() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", false);
        plan(account.getCreditAccountId(), 2);

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 1, "PROVIDER_TECHNICAL_FAILURE",
                "NO_REGISTRADA", true));

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.CANCELLED);
    }

    @Test
    @DisplayName("en una línea sólo se deshace esa disposición: el cupo vuelve, su plan se cancela y la línea sigue activa")
    void aLineOnlyUndoesThatDisposition() {
        CreditAccount account = revolvingLine();
        Disposition d = inFlight(account, "5000", true);
        List<Installment> plan = plan(d.getDispositionId(), 6);

        saga.onDisbursementFailed(new DisbursementFailure(d.getDispositionId(), 1, "INVALID_BENEFICIARY_ACCOUNT",
                "CLABE inválida", false));

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.FAILED);
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("20000");
        assertThat(account.getPrincipalBalance()).isEqualByComparingTo("0");
        assertThat(plan).allMatch(i -> i.getStatus() == InstallmentStatus.CANCELLED);
        ArgumentCaptor<DispositionFailedEvent> failed = ArgumentCaptor.forClass(DispositionFailedEvent.class);
        then(publisher).should().publishDispositionFailed(failed.capture());
        assertThat(failed.getValue().accountCancelled()).isFalse();
        then(publisher).should(never()).publishCreditAccountCancelled(any());
    }

    @Test
    @DisplayName("una devolución del banco receptor se compensa una vez, aunque llegue dos veces")
    void returnIsCompensatedOnce() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);
        d.complete("KRD2026030200000000000001");
        plan(account.getCreditAccountId(), 12);

        saga.onDisbursementReturned(d.getDispositionId(), "R01 cuenta cancelada");
        saga.onDisbursementReturned(d.getDispositionId(), "R01 cuenta cancelada");

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.REVERSED);
        assertThat(account.getStatus()).isEqualTo(CreditAccountStatus.CANCELLED);
        assertThat(account.getCancellationReason()).isEqualTo("DISBURSEMENT_RETURNED");
        then(balanceEvents).should().save(any());
        then(publisher).should().publishCreditAccountCancelled(any());
    }

    @Test
    @DisplayName("completada: la disposición se completa y se publica; repetida, no hace nada")
    void completedOnce() {
        CreditAccount account = personalLoan();
        Disposition d = inFlight(account, "50000", true);

        saga.onDisbursementCompleted(d.getDispositionId(), 1, "KRD2026030200000000000001");
        saga.onDisbursementCompleted(d.getDispositionId(), 1, "KRD2026030200000000000001");

        assertThat(d.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        then(publisher).should().publishDispositionCompleted(any());
    }

    @Test
    @DisplayName("rendimiento: diez mil desenlaces, la mitad reintentos y la mitad compensaciones, en menos de un segundo")
    void performance() {
        // Repositories as maps: what is measured is the saga, not the search of ten thousand stubs.
        Map<UUID, Disposition> byId = new HashMap<>();
        Map<UUID, CreditAccount> accountsById = new HashMap<>();
        given(dispositions.findById(any())).willAnswer(inv -> Optional.ofNullable(byId.get(inv.<UUID>getArgument(0))));
        given(accounts.findById(any())).willAnswer(inv -> Optional.ofNullable(accountsById.get(inv.<UUID>getArgument(0))));
        given(installments.findByScheduleId(any())).willReturn(List.of());
        for (int i = 0; i < 10_000; i++) {
            CreditAccount account = CreditAccount.fromSnapshot(
                    UUID.randomUUID(), "CTR-PERF-" + i, UUID.randomUUID(), "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                    new BigDecimal("50000"), null, 12, new BigDecimal("0.24"), new BigDecimal("0.36"),
                    "FRENCH", new BigDecimal("0.03"), "032180000118359719", "BAJO", null, null);
            account.activate(new BigDecimal("50000"));
            accountsById.put(account.getCreditAccountId(), account);
            Disposition d = Disposition.create(account.getCreditAccountId(), DispositionType.SELF_USE,
                    new BigDecimal("50000"), null);
            d.markProcessing();
            if (i % 2 == 0) {
                d.recordPayee("JUANA PEREZ GOMEZ", "032180000118359719", "40", "PEGJ800101AB1", "DISPOSICION DE CREDITO");
            }
            byId.put(d.getDispositionId(), d);
        }

        long started = System.nanoTime();
        for (UUID id : byId.keySet()) {
            saga.onDisbursementFailed(new DisbursementFailure(id, 1, "PROVIDER_TECHNICAL_FAILURE", "x", true));
        }
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(byId.values().stream().filter(d -> d.getStatus() == DispositionStatus.FAILED).count()).isEqualTo(5_000);
        assertThat(byId.values().stream().filter(d -> d.getAttempt() == 2).count()).isEqualTo(5_000);
        assertThat(ms).as("10,000 desenlaces en %d ms", ms).isLessThan(1_000);
    }
}
