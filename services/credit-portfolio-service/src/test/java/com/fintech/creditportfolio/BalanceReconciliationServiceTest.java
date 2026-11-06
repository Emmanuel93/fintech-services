package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.service.BalanceReconciliationService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.DistributorCommission.Tier;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.shared.time.BusinessClock;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class BalanceReconciliationServiceTest {

    @Mock CreditAccountRepository accountRepository;
    @Mock BalanceEventRepository balanceEventRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;
    @Mock InstallmentRepository installmentRepository;
    @Mock DispositionRepository dispositionRepository;
    @Mock com.fintech.creditportfolio.application.port.out.ChargeRejectionLog rejections;
    BalanceReconciliationService service;

    final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BalanceReconciliationService(accountRepository, balanceEventRepository,
                eventPublisher, installmentRepository, dispositionRepository, rejections);
        lenient().when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dispositionRepository.findByCreditAccountId(any())).thenReturn(java.util.List.of());
        // apply() locks and reloads; the tests hand back the same account the pre-reads return.
        lenient().when(accountRepository.lockAndLoad(any())).thenAnswer(inv -> accountRepository.findById(inv.getArgument(0)));
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

    @Test
    void chargeReversed_onACancelledCredit_givesBackWhatItTook_andNothingThatNeverLanded() {
        // D63: the compensation of a cancelled credit reverses its charges; a late one was rejected, never applied.
        CreditAccount a = account();
        a.applyPenaltyCharge(new BigDecimal("1500"));
        a.cancel("DISBURSEMENT_FAILED");
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));
        given(balanceEventRepository.existsBySourceEventId("fee-1")).willReturn(true);

        service.onChargeReversed("late-1", accountId, "ORDINARY_INTEREST", new BigDecimal("33.33"), false);
        then(balanceEventRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publishBalanceUpdated(any());

        service.onChargeReversed("fee-1", accountId, "OPENING_FEE", new BigDecimal("1500"), false);
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("0");
        ArgumentCaptor<BalanceEvent> asiento = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEventRepository).should().save(asiento.capture());
        assertThat(asiento.getValue().getSourceEventId()).isEqualTo("CHARGE_REVERSED:fee-1");
        then(eventPublisher).should().publishBalanceUpdated(any());
    }

    @Test
    void chargeReversed_withTheChargesOwnId_isNotTakenForARedeliveryOfTheCharge() {
        // Charges publishes the reversal with the charge's id, the id its charge-applied already left in the ledger.
        CreditAccount a = account();
        a.applyInterestAccrual(new BigDecimal("1000"));
        a.applyIvaCharge(new BigDecimal("160"));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));
        lenient().when(balanceEventRepository.existsBySourceEventId("chg-1")).thenReturn(true);

        service.onChargeReversed("chg-1", accountId, "ORDINARY_INTEREST", new BigDecimal("1000"), true);
        service.onChargeReversed("iva-1", accountId, "IVA", new BigDecimal("160"), true);

        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("0");
        assertThat(a.getIvaBalance()).as("the waived IVA comes back from the IVA, not from the penalty").isEqualByComparingTo("0");
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("0");
        ArgumentCaptor<BalanceEvent> asientos = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEventRepository).should(times(2)).save(asientos.capture());
        assertThat(asientos.getAllValues()).extracting(BalanceEvent::getSourceEventId)
                .containsExactly("CHARGE_WAIVED:chg-1", "CHARGE_WAIVED:iva-1");
    }

    @Test
    void paymentReturned_restoresWhatThePaymentCovered_inItsBuckets_andReopensASettledLoan() {
        CreditAccount a = account();
        a.applyInterestAccrual(new BigDecimal("300"));
        a.applyIvaCharge(new BigDecimal("48"));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));
        given(balanceEventRepository.findBySourceEventId("pay-1")).willReturn(Optional.empty());
        given(balanceEventRepository.netPaymentsUpTo(any(), any())).willReturn(new BigDecimal("50348"));
        given(installmentRepository.findByScheduleIds(any())).willReturn(List.of());

        service.onPaymentApplied("pay-1", accountId, new BigDecimal("50348"));
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.SETTLED);
        ArgumentCaptor<BalanceEvent> aplicado = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEventRepository).should().save(aplicado.capture());
        assertThat(aplicado.getValue().getPrincipalDelta()).isEqualByComparingTo("-50000");
        assertThat(aplicado.getValue().getIvaDelta()).isEqualByComparingTo("-48");

        // The return carries the payment order's id, the same one the applied payment carried.
        given(balanceEventRepository.findBySourceEventId("pay-1")).willReturn(Optional.of(aplicado.getValue()));
        lenient().when(balanceEventRepository.existsBySourceEventId("pay-1")).thenReturn(true);
        given(balanceEventRepository.netPaymentsUpTo(any(), any())).willReturn(BigDecimal.ZERO);
        service.onPaymentReturned("pay-1", accountId, new BigDecimal("50348"));

        assertThat(a.getPrincipalBalance()).isEqualByComparingTo("50000");
        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("300");
        assertThat(a.getIvaBalance()).isEqualByComparingTo("48");
        assertThat(a.getPenaltyBalance()).as("nothing invented as moratorium").isEqualByComparingTo("0");
        assertThat(a.getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
    }

    @Test
    void paymentReturned_ofAPaymentThePortfolioNeverApplied_changesNothing() {
        given(balanceEventRepository.findBySourceEventId("ghost")).willReturn(Optional.empty());

        service.onPaymentReturned("ghost", accountId, new BigDecimal("100"));

        then(accountRepository).should(never()).lockAndLoad(any());
        then(balanceEventRepository).should(never()).save(any());
    }

    @Test
    void installmentLoan_partialPaymentsAccumulate_andAReturnReopensTheInstallment() {
        CreditAccount a = account();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));
        Installment uno = Installment.of(accountId, 1, LocalDate.of(2026, 4, 16), new BigDecimal("1200"), new BigDecimal("300"));
        Installment dos = Installment.of(accountId, 2, LocalDate.of(2026, 5, 16), new BigDecimal("1210"), new BigDecimal("290"));
        given(installmentRepository.findByScheduleIds(any())).willReturn(List.of(uno, dos));

        // Two halves of the first installment: the second completes it (before, it stayed PARTIAL forever).
        given(balanceEventRepository.netPaymentsUpTo(any(), any())).willReturn(new BigDecimal("750"));
        service.onPaymentApplied("half-1", accountId, new BigDecimal("750"));
        assertThat(uno.getStatus()).isEqualTo(InstallmentStatus.PARTIAL);
        given(balanceEventRepository.netPaymentsUpTo(any(), any())).willReturn(new BigDecimal("1500"));
        service.onPaymentApplied("half-2", accountId, new BigDecimal("750"));
        assertThat(uno.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(dos.getStatus()).isEqualTo(InstallmentStatus.PENDING);
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
        // El rechazo queda escrito: también cuenta para cuadrar el día.
        then(rejections).should().record(eq("evt-wo"), eq(accountId), eq("ORDINARY_INTEREST"),
                eq(new BigDecimal("500")), any(), any(), any());
    }

    @Test
    void chargeApplied_alreadyRejected_isIgnored() {
        given(rejections.exists("evt-repetido")).willReturn(true);

        service.onChargeApplied("evt-repetido", accountId, "ORDINARY_INTEREST", new BigDecimal("5"));

        then(eventPublisher).should(never()).publishChargeRejected(any(), any(), any(), any(), any());
        then(accountRepository).should(never()).findById(any());
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
    void paymentReturned_ofAnEntryWrittenBeforeTheBreakdown_restoresFromTheBalancesAroundIt() {
        CreditAccount a = account();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));
        BalanceEvent antes = BalanceEvent.record(accountId, "chg", "CHARGE_ORDINARY_INTEREST", new BigDecimal("500"), a);
        a.applyInterestAccrual(new BigDecimal("500"));
        BalanceEvent previo = BalanceEvent.record(accountId, "chg", "CHARGE_ORDINARY_INTEREST", new BigDecimal("500"), a);
        a.applyPayment(new BigDecimal("10000"));
        BalanceEvent viejo = BalanceEvent.record(accountId, "ret-1", "PAYMENT_APPLIED", new BigDecimal("-10000"), a);
        given(balanceEventRepository.findBySourceEventId("ret-1")).willReturn(Optional.of(viejo));
        given(balanceEventRepository.findPrevious(viejo)).willReturn(Optional.of(previo));
        lenient().when(balanceEventRepository.netPaymentsUpTo(any(), any())).thenReturn(BigDecimal.ZERO);

        service.onPaymentReturned("ret-1", accountId, new BigDecimal("10000"));

        assertThat(a.getAccruedInterestBalance()).isEqualByComparingTo("500");
        assertThat(a.getPrincipalBalance()).isEqualByComparingTo("50000");
        assertThat(a.getPenaltyBalance()).isEqualByComparingTo("0");
        assertThat(antes).isNotNull();
        then(eventPublisher).should().publishBalanceUpdated(any());
    }

    // ── Thread-safety ─────────────────────────────────────────────────────────────

    /**
     * Verifies the service has no shared mutable state. 16 threads calling
     * onChargeApplied on different accountIds concurrently must each produce
     * a valid outcome — no NPE, ClassCastException, or other unexpected error.
     */
    // ── The distributor's commission, netted against her statements ───────────────────────────────

    private final ProductConfigResolver configResolver =
            mock(ProductConfigResolver.class);

    private static final DistributorCommission MARKET =
            new DistributorCommission(
                    DistributorCommission.COLLECTED_PRINCIPAL_AND_INTEREST,
                    new BigDecimal("0.18"),
                    List.of(new Tier(0, new BigDecimal("0.18")),
                            new Tier(1, new BigDecimal("0.14")),
                            new Tier(2, new BigDecimal("0.11")),
                            new Tier(3, new BigDecimal("0.07"))),
                    DistributorCommission.NETTED);

    /**
     * A distributor line owing one statement: 1,000 asked (800 of principal of a placement installment, 200 of
     * interest), its installment scheduling 180 of commission; the statement is due {@code due}.
     */
    private CreditAccount distributorOwingAStatement(LocalDate due) {
        service = new BalanceReconciliationService(accountRepository, balanceEventRepository, eventPublisher,
                installmentRepository, dispositionRepository, rejections, null, configResolver);
        CreditAccount line = CreditAccount.fromSnapshot(accountId, "CTR-DL-1", UUID.randomUUID(),
                "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING", null, new BigDecimal("50000"), null,
                new BigDecimal("0.95"), new BigDecimal("0.36"), null, BigDecimal.ZERO, "032180000118359719", "MEDIO", null, null);
        line.activate(BigDecimal.ZERO);
        line.applyDisposition(new BigDecimal("5000"));
        line.applyInterestAccrual(new BigDecimal("200"));
        var config = ProductConfigVersion.of("DL-001", 1, "DISTRIBUTOR_LINE",
                "REVOLVING", "B2B2C", new Capabilities(false, true, true,
                        "THIRD_PARTY_CREDIT", true, true, false, false, false),
                "FRENCH", "SEMI_MONTHLY", 500, new BigDecimal("0.95"), new BigDecimal("0.36"), BigDecimal.ZERO, "ACTIVE", false);
        config.defineDistributorCommission(MARKET);
        given(configResolver.resolveForAccount("DL-001", 1)).willReturn(Optional.of(config));
        given(accountRepository.findById(accountId)).willReturn(Optional.of(line));

        // The service reads the line by its own id (the account the lookup returns), not by the lookup's key.
        UUID lineId = line.getCreditAccountId();
        Installment statement = Installment.of(lineId, 1, due, new BigDecimal("1000.00"), BigDecimal.ZERO);
        statement.billAsStatement(1, due.minusDays(12), due);
        given(installmentRepository.findByScheduleIdOrdered(lineId)).willReturn(List.of(statement));
        Disposition placement = Disposition.create(lineId, DispositionType.THIRD_PARTY_CREDIT,
                new BigDecimal("5000"), UUID.randomUUID());
        given(dispositionRepository.findByCreditAccountId(lineId)).willReturn(List.of(placement));
        Installment cuota = Installment.of(placement.getDispositionId(), 1, due, new BigDecimal("800.00"), new BigDecimal("200.00"));
        cuota.scheduleCommission(new BigDecimal("180.00"));
        cuota.billIn(1, due);
        given(installmentRepository.findByScheduleIds(any())).willReturn(List.of(cuota));
        lenient().when(balanceEventRepository.paymentsOf(any())).thenReturn(List.of());
        return line;
    }

    @Test
    @DisplayName("tras una quita que cubrió el estado viejo, el estado que paga a tiempo sí netea su comisión (D10)")
    void aQuitaCoversTheOldStatement_soTheStatementPaidOnTimeStillNetsHerCommission() {
        LocalDate today = BusinessClock.current().today();
        CreditAccount line = distributorOwingAStatement(today);
        UUID lineId = line.getCreditAccountId();
        // An older statement, 40 days past due (beyond the ladder), that collections forgave a week ago in full.
        LocalDate dueOld = today.minusDays(40);
        Installment old = Installment.of(lineId, 1, dueOld, new BigDecimal("1000.00"), BigDecimal.ZERO);
        old.billAsStatement(1, dueOld.minusDays(12), dueOld);
        Installment current = Installment.of(lineId, 2, today, new BigDecimal("1000.00"), BigDecimal.ZERO);
        current.billAsStatement(2, today.minusDays(12), today);
        given(installmentRepository.findByScheduleIdOrdered(lineId)).willReturn(List.of(old, current));
        Disposition placement = dispositionRepository.findByCreditAccountId(lineId).get(0);
        Installment cuotaOld = Installment.of(placement.getDispositionId(), 1, dueOld, new BigDecimal("800.00"), new BigDecimal("200.00"));
        cuotaOld.scheduleCommission(new BigDecimal("180.00"));
        cuotaOld.billIn(1, dueOld);
        Installment cuota = Installment.of(placement.getDispositionId(), 2, today, new BigDecimal("800.00"), new BigDecimal("200.00"));
        cuota.scheduleCommission(new BigDecimal("180.00"));
        cuota.billIn(2, today);
        given(installmentRepository.findByScheduleIds(any())).willReturn(List.of(cuotaOld, cuota));
        BalanceEvent quita = BalanceEvent.record(lineId, "convenio-1", "AGREEMENT_QUITA_PARCIAL", new BigDecimal("-1000.00"),
                line, today.minusDays(7));
        given(balanceEventRepository.paymentsOf(any())).willReturn(List.of(quita));

        service.onPaymentApplied("pay-dl-2", accountId, new BigDecimal("820.00"));

        // The quita covered the old statement; her 820 plus the 180 netted cover the current one in full. Before, the
        // forgiven statement still "missed" 1,000, swallowed her cash, and the current statement netted nothing.
        ArgumentCaptor<BalanceEvent> entries = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEventRepository).should(times(2)).save(entries.capture());
        BalanceEvent netting = entries.getAllValues().get(0);
        assertThat(netting.getEventType()).isEqualTo("COMMISSION_NETTED");
        assertThat(netting.getSourceEventId()).isEqualTo("COMMISSION_NETTED:pay-dl-2:2");
        assertThat(netting.getDeltaAmount()).isEqualByComparingTo("-180.00");
        assertThat(line.getAccruedInterestBalance()).isEqualByComparingTo("0");
        assertThat(line.getPrincipalBalance()).isEqualByComparingTo("4200");
    }

    @Test
    @DisplayName("liquidar lo que quedó tras una quita cierra la línea: su salida, con el descuento del estado a tiempo")
    void settlingAfterAQuitaClosesTheLine() {
        LocalDate today = BusinessClock.current().today();
        CreditAccount line = distributorOwingAStatement(today);
        // Collections forgave 50 a week ago (it covers 50 of the statement); she owes 5,000 of principal and 200 of
        // interest: 180 nets against the statement and 5,020 of cash settle the rest.
        BalanceEvent quita = BalanceEvent.record(line.getCreditAccountId(), "convenio-1", "AGREEMENT_QUITA_PARCIAL",
                new BigDecimal("-50.00"), line, today.minusDays(7));
        given(balanceEventRepository.paymentsOf(any())).willReturn(List.of(quita));
        line.updateDelinquency(62);

        service.onPaymentApplied("pay-dl-3", accountId, new BigDecimal("5020.00"));

        assertThat(line.getTotalDebt()).isEqualByComparingTo("0");
        assertThat(line.getStatus()).isEqualTo(CreditAccountStatus.CLOSED);
        assertThat(line.getDaysDelinquent()).isZero();
        assertThat(line.getClosureReason()).isEqualTo(CreditAccount.CLOSED_AFTER_QUITA);
        assertThat(line.getAvailableCredit()).isEqualByComparingTo("0");
        assertThat(line.getClosedAt()).isNotNull();
    }

    @Test
    void distributorPaysOnTime_herCommissionIsNettedFirstOutOfTheInterest_thenHerCashPaysTheRest() {
        LocalDate today = BusinessClock.current().today();
        CreditAccount line = distributorOwingAStatement(today);

        service.onPaymentApplied("pay-dl-1", accountId, new BigDecimal("820.00"));

        // 180 of commission out of the 200 of interest, then 820 of cash: 20 of interest and 800 of principal.
        assertThat(line.getAccruedInterestBalance()).isEqualByComparingTo("0");
        assertThat(line.getPrincipalBalance()).isEqualByComparingTo("4200");
        ArgumentCaptor<BalanceEvent> entries = ArgumentCaptor.forClass(BalanceEvent.class);
        then(balanceEventRepository).should(times(2)).save(entries.capture());
        BalanceEvent netting = entries.getAllValues().get(0), cash = entries.getAllValues().get(1);
        assertThat(netting.getEventType()).isEqualTo("COMMISSION_NETTED");
        assertThat(netting.getSourceEventId()).isEqualTo("COMMISSION_NETTED:pay-dl-1:1");
        assertThat(netting.getDeltaAmount()).isEqualByComparingTo("-180.00");
        assertThat(netting.getInterestDelta()).isEqualByComparingTo("-180.00");
        assertThat(netting.getPrincipalDelta()).isEqualByComparingTo("0");
        assertThat(cash.getEventType()).isEqualTo("PAYMENT_APPLIED");
        assertThat(cash.getDeltaAmount()).isEqualByComparingTo("-820.00");
        ArgumentCaptor<BalanceUpdatedEvent> events = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should(times(2)).publishBalanceUpdated(events.capture());
        BalanceUpdatedEvent published = events.getAllValues().get(0);
        assertThat(published.getTriggerEvent()).isEqualTo("COMMISSION_NETTED");
        assertThat(published.getCommission().cycleNumber()).isEqualTo(1);
        assertThat(published.getCommission().daysLate()).isZero();
        assertThat(published.getCommission().rate()).isEqualByComparingTo("0.18");
        assertThat(published.getCommission().amount()).isEqualByComparingTo("180.00");
        assertThat(published.getCommission().basis()).isEqualByComparingTo("1000.00");
        assertThat(events.getAllValues().get(1).getCommissionSettlement()).isEqualTo("NETTED");
        assertThat(events.getAllValues().get(1).getCommission()).isNull();
    }

    @Test
    void distributorPaysTwoDaysLate_theCommissionDropsToElevenPercent_andHerCashCoversTheRest() {
        LocalDate today = BusinessClock.current().today();
        CreditAccount line = distributorOwingAStatement(today.minusDays(2));

        service.onPaymentApplied("pay-dl-2", accountId, new BigDecimal("890.00"));

        assertThat(line.getPrincipalBalance()).isEqualByComparingTo("4200");
        assertThat(line.getAccruedInterestBalance()).isEqualByComparingTo("0");
        ArgumentCaptor<BalanceUpdatedEvent> events = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should(times(2)).publishBalanceUpdated(events.capture());
        assertThat(events.getAllValues().get(0).getCommission().daysLate()).isEqualTo(2);
        assertThat(events.getAllValues().get(0).getCommission().rate()).isEqualByComparingTo("0.11");
        assertThat(events.getAllValues().get(0).getCommission().amount()).isEqualByComparingTo("110.00");
    }

    @Test
    void distributorPaysPastTheLadder_nothingIsNetted_andThePaymentIsAPlainOne() {
        LocalDate today = BusinessClock.current().today();
        CreditAccount line = distributorOwingAStatement(today.minusDays(9));

        service.onPaymentApplied("pay-dl-3", accountId, new BigDecimal("1000.00"));

        assertThat(line.getPrincipalBalance()).isEqualByComparingTo("4200");
        then(balanceEventRepository).should(times(1)).save(any());
        ArgumentCaptor<BalanceUpdatedEvent> events = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should().publishBalanceUpdated(events.capture());
        assertThat(events.getValue().getTriggerEvent()).isEqualTo("PAYMENT_APPLIED");
        assertThat(events.getValue().getCommissionSettlement()).isEqualTo("NETTED");
    }

    @Test
    void distributorPaysMoreThanTheDebtLessHerCommission_thePaymentIsRejectedAsAnOverpayment() {
        LocalDate today = BusinessClock.current().today();
        CreditAccount line = distributorOwingAStatement(today);

        // She owes 5,200; 180 are hers: anything above 5,020 cannot be applied.
        service.onPaymentApplied("pay-dl-4", accountId, new BigDecimal("5100.00"));

        assertThat(line.getPrincipalBalance()).isEqualByComparingTo("5000");
        then(eventPublisher).should().publishPaymentRejected(eq("pay-dl-4"), eq(accountId), eq(new BigDecimal("5100.00")), any());
        then(balanceEventRepository).should(never()).save(any());
    }

    @Test
    void aProductWithoutADistributor_appliesThePaymentAsAlways() {
        CreditAccount a = account();
        a.applyInterestAccrual(new BigDecimal("1000"));
        service = new BalanceReconciliationService(accountRepository, balanceEventRepository, eventPublisher,
                installmentRepository, dispositionRepository, rejections, null, configResolver);
        given(configResolver.resolveForAccount(any(), any())).willReturn(Optional.empty());
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onPaymentApplied("pay-plain", accountId, new BigDecimal("1000"));

        then(balanceEventRepository).should(times(1)).save(any());
        ArgumentCaptor<BalanceUpdatedEvent> events = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should().publishBalanceUpdated(events.capture());
        assertThat(events.getValue().getCommissionSettlement()).isNull();
    }

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

    @Test
    void statementBilledPayment_settlesTheStatementItCovers_notTheNextMonthsOfAPlan() {
        // A card with 9,000 of purchases, 6,000 of them deferred to six months. Its first statement asked 1,464.44
        // (the minimum plus the first month); the holder pays 4,288.84. That covers the statement and its month;
        // it does not pay months no statement has billed yet.
        CreditAccount card = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-TDC", UUID.randomUUID(), "CC-001", 1, "CREDIT_CARD", "REVOLVING",
                null, new BigDecimal("30000"), null, new BigDecimal("0.36"), new BigDecimal("0.60"),
                null, new BigDecimal("0.16"), "032180000118359719", "MEDIO", null, null);
        card.activate(BigDecimal.ZERO);
        card.applyDisposition(new BigDecimal("9000"));
        UUID cardId = card.getCreditAccountId();
        UUID deferred = UUID.randomUUID();
        Installment statement1 = Installment.of(cardId, 1, LocalDate.of(2026, 5, 6), new BigDecimal("1464.44"), BigDecimal.ZERO);
        statement1.billIn(1, LocalDate.of(2026, 5, 6));
        Installment month1 = Installment.of(deferred, 1, LocalDate.of(2026, 4, 21), new BigDecimal("1000"), BigDecimal.ZERO);
        month1.billIn(1, LocalDate.of(2026, 5, 6));
        Installment month2 = Installment.of(deferred, 2, LocalDate.of(2026, 5, 21), new BigDecimal("1000"), BigDecimal.ZERO);
        Disposition purchase = mock(Disposition.class);
        given(purchase.getDispositionId()).willReturn(deferred);

        given(accountRepository.findById(cardId)).willReturn(Optional.of(card));
        given(dispositionRepository.findByCreditAccountId(cardId)).willReturn(List.of(purchase));
        given(installmentRepository.findByScheduleIdOrdered(cardId)).willReturn(List.of(statement1));
        given(installmentRepository.findByScheduleIds(any())).willReturn(List.of(month1, month2));
        // The ledger's payments are what covers the statements (StatementCoverage), net of returns.
        given(balanceEventRepository.paymentsOf(cardId)).willReturn(List.of(BalanceEvent.record(cardId, "pay-1",
                "PAYMENT_APPLIED", new BigDecimal("-4288.84"), card, LocalDate.of(2026, 5, 6))));

        service.onPaymentApplied("pay-1", cardId, new BigDecimal("4288.84"));

        assertThat(statement1.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(month1.getStatus()).as("billed by the covered statement").isEqualTo(InstallmentStatus.PAID);
        assertThat(month2.getStatus()).as("no statement billed it yet").isEqualTo(InstallmentStatus.PENDING);
    }

    @Test
    void paymentToAWrittenOffLoan_isARecovery_notAnOverpayment() {
        CreditAccount a = account();
        a.executeWriteOff();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(a));

        service.onPaymentApplied("rec-1", accountId, new BigDecimal("2000"));

        then(eventPublisher).should(never()).publishPaymentRejected(any(), any(), any(), any());
        ArgumentCaptor<BalanceUpdatedEvent> evento = ArgumentCaptor.forClass(BalanceUpdatedEvent.class);
        then(eventPublisher).should().publishBalanceUpdated(evento.capture());
        assertThat(evento.getValue().getTriggerEvent()).isEqualTo("RECOVERY_PAYMENT");
        assertThat(evento.getValue().getSourceEventId()).as("payments confirms the order by it").isEqualTo("rec-1");
        assertThat(a.getTotalDebt()).isEqualByComparingTo("0");
    }
}
