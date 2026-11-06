package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.in.StatementViewUseCase.LadderStep;
import com.fintech.creditportfolio.application.port.in.StatementViewUseCase.PlacementLine;
import com.fintech.creditportfolio.application.port.in.StatementViewUseCase.StatementView;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.DistributorCommission.Tier;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CollectionAccountProjection;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.shared.time.BusinessClock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La vista del estado de cuenta: qué se suma, qué se lista y qué cuenta como «del período».
 */
class StatementViewServiceTest {

    private static final LocalDate DESDE = LocalDate.of(2026, 11, 1);
    private static final LocalDate HASTA = LocalDate.of(2026, 11, 15);
    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    /** Where the line's money goes out to (her own bank account): never where she pays. */
    private static final String DISBURSEMENT_CLABE = "002180151461387922";
    private static final String COLLECTION_CLABE = "646180378000000032";

    private final FindCreditAccountUseCase accounts = mock(FindCreditAccountUseCase.class);
    private final BalanceEventRepository ledger = mock(BalanceEventRepository.class);
    private final InstallmentRepository installments = mock(InstallmentRepository.class);
    private final DispositionRepository dispositions = mock(DispositionRepository.class);
    private final CollectionAccountProjection collectionAccounts = mock(CollectionAccountProjection.class);
    private final StatementViewService service =
            new StatementViewService(accounts, ledger, installments, dispositions, null, collectionAccounts);

    private CreditAccount linea;
    private final List<BalanceEvent> libro = new ArrayList<>();

    @BeforeEach
    void cuenta() {
        linea = CreditAccount.fromSnapshot(UUID.randomUUID(), "CTR-202611-0001", UUID.randomUUID(), "DL-DIST-STD-V1",
                1, "DISTRIBUTOR_LINE", "REVOLVING", null, new BigDecimal("50000"), 24, new BigDecimal("0.60"),
                new BigDecimal("0.90"), "FRENCH", BigDecimal.ZERO, DISBURSEMENT_CLABE, "B", null, null);
        when(accounts.getById(linea.getCreditAccountId())).thenReturn(linea);
        when(ledger.findBetween(eq(linea.getCreditAccountId()), any(), any())).thenAnswer(inv -> {
            LocalDate desde = inv.getArgument(1), hasta = inv.getArgument(2);
            return libro.stream().filter(e -> !e.getValueDate().isBefore(desde) && !e.getValueDate().isAfter(hasta)).toList();
        });
        when(ledger.findLastBefore(eq(linea.getCreditAccountId()), any())).thenAnswer(inv -> {
            LocalDate antes = inv.getArgument(1);
            return libro.stream().filter(e -> e.getValueDate().isBefore(antes)).reduce((a, b) -> b);
        });
        when(installments.findByScheduleIdOrdered(linea.getCreditAccountId())).thenReturn(List.of());
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(List.of());
    }

    @AfterEach
    void relojReal() {
        BusinessClock.current().resetShift();
    }

    /** Runs {@code action} with the business day set to {@code day}: what it pays is paid that day. */
    private static void on(LocalDate day, Runnable action) {
        BusinessClock.current().shiftTo(day);
        try {
            action.run();
        } finally {
            BusinessClock.current().resetShift();
        }
    }

    private static Disposition placement(CreditAccount account, String amount, LocalDate on) {
        Disposition[] d = new Disposition[1];
        on(on, () -> d[0] = Disposition.create(account.getCreditAccountId(), DispositionType.THIRD_PARTY_CREDIT,
                new BigDecimal(amount), UUID.randomUUID()));
        return d[0];
    }

    @Test
    @DisplayName("la vista dice pagar a la CLABE de cobro de la titular, nunca a la de desembolso de la cuenta")
    void theViewCarriesTheCollectionClabe() throws JsonProcessingException {
        when(collectionAccounts.activeClabes(List.of(linea.getObligorPartyId())))
                .thenReturn(Map.of(linea.getObligorPartyId(), COLLECTION_CLABE));

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, null);

        assertThat(v.account().collectionClabe()).isEqualTo(COLLECTION_CLABE).isNotEqualTo(DISBURSEMENT_CLABE);
        // The contract statements and receipts read: the key is «collectionClabe», and the disbursement CLABE is not sent.
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(v.account());
        assertThat(json).contains("\"collectionClabe\":\"" + COLLECTION_CLABE + "\"").doesNotContain(DISBURSEMENT_CLABE);
    }

    @Test
    @DisplayName("sin CLABE de cobro activa la vista no inventa una: el estado dice pagar por la app")
    void withoutAnActiveCollectionClabeTheViewHasNone() {
        when(collectionAccounts.activeClabes(List.of(linea.getObligorPartyId()))).thenReturn(Map.of());

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, null);

        assertThat(v.account().collectionClabe()).isNull();
    }

    @Test
    @DisplayName("el devengo diario llega sumado y lo que el cliente reconoce como hecho se lista")
    void sumaElDevengoYListaLosHechos() {
        movimiento("2026-10-31T18:00:00Z", "DISPOSITION_THIRD_PARTY_CREDIT", "8000", "8000");      // antes
        for (int dia = 1; dia <= 15; dia++) {
            // El devengo del día se aplica a las 00:05 del siguiente en México (06:05 UTC) y es de ese día.
            String t = String.format("2026-11-%02dT06:05:00Z", dia + 1);
            LocalDate delDia = LocalDate.of(2026, 11, dia);
            String capital = dia < 5 ? "8000" : dia < 10 ? "14000" : "12700";   // el devengo lleva el capital del momento
            movimiento(t, delDia, "CHARGE_ORDINARY_INTEREST", "13.33", capital);
            movimiento(t, delDia, "CHARGE_IVA", "2.13", capital);
        }
        movimiento("2026-11-05T17:00:00Z", "DISPOSITION_THIRD_PARTY_CREDIT", "6000", "14000");
        movimiento("2026-11-10T17:00:00Z", "PAYMENT_APPLIED", "-1500", "12700");
        movimiento("2026-11-10T17:00:01Z", "CHARGE_ADMIN_FEE", "150", "12700");

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, null);

        assertThat(v.opening().principal()).isEqualByComparingTo("8000");
        assertThat(v.totals().ordinaryInterest()).isEqualByComparingTo("199.95");
        assertThat(v.totals().iva()).isEqualByComparingTo("31.95");
        assertThat(v.totals().dispositions()).isEqualByComparingTo("6000");
        assertThat(v.totals().payments()).isEqualByComparingTo("1500");
        assertThat(v.totals().fees()).isEqualByComparingTo("150");
        assertThat(v.movements()).extracting(m -> m.type())
                .containsExactly("DISPOSITION_THIRD_PARTY_CREDIT", "PAYMENT_APPLIED", "CHARGE_ADMIN_FEE");
        assertThat(v.closing().principal()).isEqualByComparingTo("12700");
        assertThat(v.dueUntil()).isEqualTo(HASTA);
    }

    @Test
    @DisplayName("un pago de las 19:00 del día del corte en México es del período, aunque en UTC ya sea el día siguiente")
    void elPeriodoEsDeDiasDeNegocio() {
        movimiento("2026-11-16T01:00:00Z", "PAYMENT_APPLIED", "-500", "0");   // 19:00 del 15 en México
        movimiento("2026-11-16T07:00:00Z", "PAYMENT_APPLIED", "-700", "0");   // 01:00 del 16 en México

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, null);

        assertThat(v.totals().payments()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("el devengo del día de corte se aplica a las 00:05 del siguiente y es del período; el de la víspera no")
    void elDevengoVaPorFechaValor() {
        movimiento("2026-11-01T06:05:00Z", LocalDate.of(2026, 10, 31), "CHARGE_ORDINARY_INTEREST", "9.00", "8000");
        movimiento("2026-11-16T06:05:00Z", HASTA, "CHARGE_ORDINARY_INTEREST", "13.33", "8000");
        movimiento("2026-11-16T06:02:00Z", "PAYMENT_APPLIED", "-300", "8000");   // 00:02 del 16: del siguiente

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, null);

        assertThat(v.totals().ordinaryInterest()).isEqualByComparingTo("13.33");
        assertThat(v.totals().payments()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("cada colocación trae lo que le toca pagar hasta la fecha límite y cuánto lleva pagado")
    void lasColocacionesTraenLoQueTocaPagar() {
        Disposition a = placement(linea, "6000", LocalDate.of(2026, 10, 31));
        Disposition[] own = new Disposition[1];
        on(LocalDate.of(2026, 11, 2), () -> own[0] = Disposition.create(linea.getCreditAccountId(),
                DispositionType.SELF_USE, new BigDecimal("1000"), null));
        Disposition propia = own[0];
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(List.of(a, propia));

        List<Installment> cuotas = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            cuotas.add(Installment.of(a.getDispositionId(), n, LocalDate.of(2026, 10, 31).plusDays(15L * n),
                    new BigDecimal("1000"), new BigDecimal("120"), new BigDecimal("19.20")));
        }
        on(LocalDate.of(2026, 11, 14), () -> cuotas.get(0).applyPayment(cuotas.get(0).getTotalAmount()));   // la primera ya se pagó
        when(installments.findByScheduleIds(any(Collection.class))).thenReturn(cuotas);

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, LocalDate.of(2026, 11, 30));

        assertThat(v.placements()).singleElement().satisfies(p -> {
            assertThat(p.beneficiaryPartyId()).isEqualTo(a.getBeneficiaryPartyId());
            assertThat(p.totalInstallments()).isEqualTo(6);
            assertThat(p.paidInstallments()).isEqualTo(1);
            assertThat(p.outstandingPrincipal()).isEqualByComparingTo("5000");
            assertThat(p.dueInPeriod()).extracting(i -> i.number()).containsExactly(2);   // vence el 30
            assertThat(p.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        });
    }

    @Test
    @DisplayName("un período que termina antes de empezar se rechaza")
    void periodoInvalido() {
        assertThatThrownBy(() -> service.view(linea.getCreditAccountId(), HASTA, DESDE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rendimiento: un año de devengo y 50 colocaciones de 24 cuotas se arman en milisegundos")
    void rendimiento() {
        Instant t = Instant.parse("2025-11-01T06:00:00Z");
        for (int i = 0; i < 730; i++) movimiento(t.plusSeconds(43_200L * i).toString(), "CHARGE_ORDINARY_INTEREST", "10", "8000");
        List<Disposition> colocaciones = new ArrayList<>();
        List<Installment> cuotas = new ArrayList<>();
        for (int c = 0; c < 50; c++) {
            Disposition d = placement(linea, "6000", LocalDate.of(2025, 12, 20));
            colocaciones.add(d);
            for (int n = 1; n <= 24; n++) {
                cuotas.add(Installment.of(d.getDispositionId(), n, LocalDate.of(2026, 1, 1).plusDays(15L * n),
                        new BigDecimal("250"), new BigDecimal("30")));
            }
        }
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(colocaciones);
        when(installments.findByScheduleIds(any(Collection.class))).thenReturn(cuotas);

        long inicio = System.nanoTime();
        for (int i = 0; i < 200; i++) service.view(linea.getCreditAccountId(), DESDE, HASTA, null);
        long promedioMicros = (System.nanoTime() - inicio) / 200 / 1_000;

        assertThat(promedioMicros).as("µs por vista").isLessThan(20_000L);
    }

    /** Un movimiento del día en que se aplicó (en hora de México). */
    private void movimiento(String instante, String tipo, String delta, String capitalDespues) {
        movimiento(instante, Instant.parse(instante).atZone(MX).toLocalDate(), tipo, delta, capitalDespues);
    }

    private void movimiento(String instante, LocalDate fechaValor, String tipo, String delta, String capitalDespues) {
        BalanceEvent e = BalanceEvent.record(linea.getCreditAccountId(), UUID.randomUUID().toString(), tipo,
                new BigDecimal(delta), linea, fechaValor);
        ReflectionTestUtils.setField(e, "appliedAt", Instant.parse(instante));
        ReflectionTestUtils.setField(e, "principalAfter", new BigDecimal(capitalDespues));
        libro.add(e);
        libro.sort(Comparator.comparing(BalanceEvent::getValueDate).thenComparing(BalanceEvent::getAppliedAt));
    }

    @SuppressWarnings("unused")
    private static Optional<BalanceEvent> none() { return Optional.empty(); }

    // --- what a statement of a statement-billed product asks for -------------------------------------

    private static final LocalDate CUTOFF_N = LocalDate.of(2026, 4, 13);
    private static final LocalDate DUE_N    = LocalDate.of(2026, 4, 28);

    @Test
    @DisplayName("la escalera de la distribuidora en el estado: su comisión por el día en que paga, y los dos números de cada colocación")
    void theDistributorsLadderAndHerTwoNumbers() {
        ProductConfigResolver resolver = mock(ProductConfigResolver.class);
        ProductConfigVersion config = ProductConfigVersion.of("DL-DIST-STD-V1", 1, "DISTRIBUTOR_LINE", "REVOLVING", "B2B2C",
                new Capabilities(false, true, true, "THIRD_PARTY_CREDIT", true, true, false, false, false),
                "FRENCH", "MONTHLY", 500, new BigDecimal("0.95"), new BigDecimal("0.36"), BigDecimal.ZERO, "ACTIVE", false);
        config.defineDistributorCommission(new DistributorCommission(DistributorCommission.COLLECTED_PRINCIPAL_AND_INTEREST,
                new BigDecimal("0.18"), List.of(new Tier(0, new BigDecimal("0.18")), new Tier(1, new BigDecimal("0.14")),
                        new Tier(2, new BigDecimal("0.11")), new Tier(3, new BigDecimal("0.07"))), DistributorCommission.NETTED));
        when(resolver.resolveForAccount("DL-DIST-STD-V1", 1)).thenReturn(Optional.of(config));
        StatementViewService conComision = new StatementViewService(accounts, ledger, installments, dispositions, resolver, collectionAccounts);

        // One placement: 450 of principal and 50 of interest a fortnight, 90 of commission on each; the first one is
        // billed by this statement (due 20 November), the second is December's.
        Disposition placement = placement(linea, "900", LocalDate.of(2026, 11, 2));
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(List.of(placement));
        Installment primera = Installment.of(placement.getDispositionId(), 1, LocalDate.of(2026, 11, 20),
                new BigDecimal("450.00"), new BigDecimal("50.00"), new BigDecimal("8.00"));
        Installment segunda = Installment.of(placement.getDispositionId(), 2, LocalDate.of(2026, 12, 5),
                new BigDecimal("450.00"), new BigDecimal("50.00"), new BigDecimal("8.00"));
        primera.scheduleCommission(new BigDecimal("90.00"));
        segunda.scheduleCommission(new BigDecimal("90.00"));
        when(installments.findByScheduleIds(any(Collection.class))).thenReturn(List.of(primera, segunda));

        StatementView v = conComision.view(linea.getCreditAccountId(), DESDE, HASTA, LocalDate.of(2026, 11, 30));

        assertThat(v.commissionSettlement()).isEqualTo("NETTED");
        assertThat(v.commissionLadder()).extracting(LadderStep::daysLate).containsExactly(0, 1, 2, 3, 4);
        assertThat(v.commissionLadder()).extracting(LadderStep::commission)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("90.00"), new BigDecimal("70.00"), new BigDecimal("55.00"),
                        new BigDecimal("35.00"), BigDecimal.ZERO);
        PlacementLine p = v.placements().get(0);
        assertThat(p.commissionPerPayment()).isEqualByComparingTo("90.00");
        assertThat(p.commissionTotal()).isEqualByComparingTo("180.00");
        assertThat(p.interestTotal()).isEqualByComparingTo("100.00");
        // 180 of commission on 100 of interest: capped plans never get here, but the number is the plan's own.
        assertThat(p.commissionShareOfInterest()).isEqualByComparingTo("1.8000");

        // Without the terms (another product) nothing of this appears.
        StatementView plain = service.view(linea.getCreditAccountId(), DESDE, HASTA, LocalDate.of(2026, 11, 30));
        assertThat(plain.commissionLadder()).isEmpty();
        assertThat(plain.commissionSettlement()).isNull();
        assertThat(plain.placements().get(0).commissionPerPayment()).isNull();
    }

    private static Installment cuota(LocalDate due, Integer billedCycle, LocalDate billedDue) {
        Installment i = Installment.of(UUID.randomUUID(), 1, due, new BigDecimal("9000"), new BigDecimal("455"));
        if (billedCycle != null) i.billIn(billedCycle, billedDue);
        return i;
    }

    @Test
    @DisplayName("lo que cobró el estado anterior y aún no vence al corte no se vuelve a pedir")
    void previousStatementsInstallmentStillWithinItsWindowIsNotAskedAgain() {
        // Billed by the statement of 30 March (due 14 April): on the cutoff of 13 April it is still not due.
        Installment billedBefore = cuota(LocalDate.of(2026, 3, 31), 1, LocalDate.of(2026, 4, 14));
        assertThat(StatementViewService.dueInThisStatement(billedBefore, CUTOFF_N, DUE_N)).isFalse();
    }

    @Test
    @DisplayName("lo de este estado sí: facturado con su fecha límite, o aún sin facturar y venciendo hasta ella")
    void thisStatementsInstallmentsAreAsked() {
        assertThat(StatementViewService.dueInThisStatement(cuota(LocalDate.of(2026, 4, 28), 2, DUE_N), CUTOFF_N, DUE_N)).isTrue();
        assertThat(StatementViewService.dueInThisStatement(cuota(LocalDate.of(2026, 4, 28), null, null), CUTOFF_N, DUE_N)).isTrue();
        assertThat(StatementViewService.dueInThisStatement(cuota(LocalDate.of(2026, 5, 12), null, null), CUTOFF_N, DUE_N)).isFalse();
    }

    @Test
    @DisplayName("Semana Santa: el estado del 6 de abril vence el 21, día del corte siguiente; la cuota de ese corte no es suya")
    void holyWeekLeavesTheNextCutoffsInstallmentToItsStatement() {
        LocalDate corte = LocalDate.of(2025, 4, 6), vence = LocalDate.of(2025, 4, 21), hasta = LocalDate.of(2025, 4, 20);
        // Its own installment, billed by it: its due date moved to the 21st.
        Installment suya = cuota(corte, 7, vence);
        // The next cutoff's, not billed yet: dated on the 21st, the same day as this statement's due date.
        Installment delSiguiente = cuota(vence, null, null);

        assertThat(StatementViewService.dueInThisStatement(suya, corte, vence, hasta)).isTrue();
        assertThat(StatementViewService.billedByThisStatement(suya, corte, vence, hasta)).isTrue();
        assertThat(StatementViewService.dueInThisStatement(delSiguiente, corte, vence, hasta)).isFalse();
        assertThat(StatementViewService.billedByThisStatement(delSiguiente, corte, vence, hasta)).isFalse();
        // Billing up to the due date, as before, asked for it a fortnight early.
        assertThat(StatementViewService.dueInThisStatement(delSiguiente, corte, vence)).isTrue();

        // On the 21 April statement (paid by 5 May) it is billed and asked for.
        Installment yaFacturada = cuota(vence, 8, LocalDate.of(2025, 5, 5));
        assertThat(StatementViewService.dueInThisStatement(yaFacturada, vence, LocalDate.of(2025, 5, 5),
                LocalDate.of(2025, 5, 5))).isTrue();
    }

    @Test
    @DisplayName("lo vencido al corte y sin pagar se pide: es atraso")
    void arrearsAreAsked() {
        Installment late = cuota(LocalDate.of(2026, 3, 31), 1, LocalDate.of(2026, 4, 1));
        assertThat(StatementViewService.dueInThisStatement(late, CUTOFF_N, DUE_N)).isTrue();
    }

    @Test
    @DisplayName("statement arrears: paying one statement in full does not cover the next one's minimum (E34)")
    void arrearsCountOnlyPaymentsMadeAfterEachStatement() {
        // Statement 1 (cut 2 Apr, due 22 Apr) asked 517.40; statement 2 (cut 4 May, due 25 May) asked 217.97.
        Installment primero = Installment.of(linea.getCreditAccountId(), 1, LocalDate.of(2026, 4, 22),
                new BigDecimal("517.40"), BigDecimal.ZERO);
        primero.billAsStatement(1, LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 22));
        Installment segundo = Installment.of(linea.getCreditAccountId(), 2, LocalDate.of(2026, 5, 25),
                new BigDecimal("217.97"), BigDecimal.ZERO);
        segundo.billAsStatement(2, LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 25));
        when(installments.findByScheduleIdOrdered(linea.getCreditAccountId())).thenReturn(List.of(primero, segundo));
        // The whole balance of statement 1 paid on its due date; nothing after statement 2 was cut.
        movimiento("2026-04-22T18:00:00Z", "PAYMENT_APPLIED", "-10348.00", "0");
        when(ledger.paymentsOf(linea.getCreditAccountId())).thenReturn(List.copyOf(libro));

        StatementView v = service.view(linea.getCreditAccountId(), LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 2),
                LocalDate.of(2026, 6, 22));

        assertThat(v.arrears()).isEqualByComparingTo("217.97");
    }

    @Test
    @DisplayName("placements: an old settled one is left out, the one settled in the period is reported once, an overdue one says how many and since when")
    void placementsLiveSettledAndOverdue() {
        Disposition old = placement(linea, "2000", LocalDate.of(2026, 9, 1));
        Disposition settledNow = placement(linea, "2000", LocalDate.of(2026, 9, 20));
        Disposition late = placement(linea, "3000", LocalDate.of(2026, 9, 25));
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(List.of(old, settledNow, late));

        List<Installment> all = new ArrayList<>();
        // Paid off in September: its plan ended before this period.
        for (int n = 1; n <= 2; n++) {
            Installment i = Installment.of(old.getDispositionId(), n, LocalDate.of(2026, 9, 1).plusDays(15L * n),
                    new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO);
            on(i.getDueDate(), () -> i.applyPayment(i.getTotalAmount()));
            all.add(i);
        }
        // Paid ahead in this period (10 Nov): its last installment falls due in December.
        for (int n = 1; n <= 2; n++) {
            Installment i = Installment.of(settledNow.getDispositionId(), n, LocalDate.of(2026, 11, 20).plusDays(15L * n),
                    new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO);
            on(LocalDate.of(2026, 11, 10), () -> i.applyPayment(i.getTotalAmount()));
            all.add(i);
        }
        // Two installments due before the cutoff (15 Nov) unpaid, one to come.
        for (int n = 1; n <= 3; n++) {
            all.add(Installment.of(late.getDispositionId(), n, LocalDate.of(2026, 10, 1).plusDays(15L * n),
                    new BigDecimal("1000"), new BigDecimal("50"), new BigDecimal("8")));
        }
        when(installments.findByScheduleIds(any(Collection.class))).thenReturn(all);

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, LocalDate.of(2026, 11, 30));

        assertThat(v.placements()).extracting(p -> p.dispositionId())
                .containsExactly(settledNow.getDispositionId(), late.getDispositionId());
        assertThat(v.placements().get(0).paidOffInPeriod()).isTrue();
        assertThat(v.placements().get(0).settledOn()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(v.placements().get(0).lastDueDate()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(v.placements().get(0).interestFree()).isTrue();
        assertThat(v.placements().get(1).overdueInstallments()).isEqualTo(2);
        assertThat(v.placements().get(1).oldestOverdueDue()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(v.placements().get(1).paidOffInPeriod()).isFalse();
        assertThat(v.purchasePlans()).isEmpty();
    }

    @Test
    @DisplayName("a placement paid ahead is reported settled in the period it was paid, and never again (DIST0005)")
    void aPlacementPaidAheadIsReportedOnce() {
        // Placed in October with 24 fortnightly installments up to 2027, all paid on 10 Nov.
        Disposition prepaid = placement(linea, "24000", LocalDate.of(2026, 10, 1));
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(List.of(prepaid));
        List<Installment> plan = new ArrayList<>();
        for (int n = 1; n <= 24; n++) {
            Installment i = Installment.of(prepaid.getDispositionId(), n, LocalDate.of(2026, 10, 15).plusDays(15L * n),
                    new BigDecimal("1000"), new BigDecimal("20"), new BigDecimal("3.20"));
            on(LocalDate.of(2026, 11, 10), () -> i.applyPayment(i.getTotalAmount()));
            plan.add(i);
        }
        when(installments.findByScheduleIds(any(Collection.class))).thenReturn(plan);

        StatementView november = service.view(linea.getCreditAccountId(), DESDE, HASTA, LocalDate.of(2026, 11, 30));
        StatementView next = service.view(linea.getCreditAccountId(), LocalDate.of(2026, 11, 16),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 15));
        StatementView january = service.view(linea.getCreditAccountId(), LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 30));

        assertThat(november.placements()).singleElement().satisfies(p -> {
            assertThat(p.paidOffInPeriod()).isTrue();
            assertThat(p.settledOn()).isEqualTo(LocalDate.of(2026, 11, 10));
            assertThat(p.outstandingPrincipal()).isEqualByComparingTo("0");
        });
        // Its plan runs to 2027, but it was settled in November: the following statements no longer list it.
        assertThat(next.placements()).isEmpty();
        assertThat(january.placements()).isEmpty();
    }

    @Test
    @DisplayName("the plan is read at the cutoff: an installment paid after it was still owed, a placement made after it is the next statement's")
    void thePlanAsItStoodAtTheCutoff() {
        Disposition live = placement(linea, "2000", LocalDate.of(2026, 10, 20));
        Disposition afterTheCutoff = placement(linea, "5000", LocalDate.of(2026, 11, 20));
        when(dispositions.findByCreditAccountId(linea.getCreditAccountId())).thenReturn(List.of(live, afterTheCutoff));
        Installment first = Installment.of(live.getDispositionId(), 1, LocalDate.of(2026, 11, 10),
                new BigDecimal("1000"), new BigDecimal("30"), new BigDecimal("4.80"));
        Installment second = Installment.of(live.getDispositionId(), 2, LocalDate.of(2026, 11, 25),
                new BigDecimal("1000"), new BigDecimal("15"), new BigDecimal("2.40"));
        // Both paid on 18 Nov, after the cutoff of the 15th.
        on(LocalDate.of(2026, 11, 18), () -> {
            first.applyPayment(first.getTotalAmount());
            second.applyPayment(second.getTotalAmount());
        });
        Installment laterPlacement = Installment.of(afterTheCutoff.getDispositionId(), 1, LocalDate.of(2026, 12, 5),
                new BigDecimal("5000"), new BigDecimal("75"), new BigDecimal("12"));
        when(installments.findByScheduleIds(any(Collection.class))).thenAnswer(inv -> {
            Collection<UUID> ids = inv.getArgument(0);
            return List.of(first, second, laterPlacement).stream().filter(i -> ids.contains(i.getScheduleId())).toList();
        });

        StatementView v = service.view(linea.getCreditAccountId(), DESDE, HASTA, LocalDate.of(2026, 11, 30));

        assertThat(v.placements()).singleElement().satisfies(p -> {
            assertThat(p.dispositionId()).isEqualTo(live.getDispositionId());
            assertThat(p.paidInstallments()).isZero();
            assertThat(p.overdueInstallments()).isEqualTo(1);
            assertThat(p.outstandingPrincipal()).isEqualByComparingTo("2000");
            assertThat(p.paidOffInPeriod()).isFalse();
        });
        assertThat(v.plansOutstanding()).isEqualByComparingTo("2052.20");
    }
}
