package com.fintech.creditportfolio.revolving;

import com.fintech.creditportfolio.application.port.in.CloseCutoffCycleUseCase.CorteCerrado;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.CycleBillingService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.shared.time.BusinessClock;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El exigible del corte, que es lo que le da a una revolvente algo que vencer.
 *
 * <pre>
 * exigible = Σ compras del ciclo  −  Σ compras diferidas  +  Σ cuotas de planes vigentes
 * </pre>
 *
 * <p><b>Por qué esto y la revolvente pura son un solo cambio (AN-20).</b> El envejecido busca
 * cuotas vencidas. Una compra con tarjeta que nace sin calendario no tiene ninguna, así que si se
 * le quita el calendario y no se le da al corte algo que exigir, la línea sale invariablemente con
 * cero días de atraso — y ese cero arrastra a riesgo, a cobranza y al quebranto detrás.
 */
@ExtendWith(MockitoExtension.class)
class CutoffAmountDueTest {

    @Mock CreditAccountRepository accounts;
    @Mock DispositionRepository dispositions;
    @Mock InstallmentRepository installments;

    CycleBillingService servicio;

    private final UUID accountId = UUID.randomUUID();
    private final List<Disposition> ofTheLine = new ArrayList<>();

    /**
     * El corte se sella en la fecha de negocio del día. Las compras se crean con el reloj del
     * sistema, así que fijar el corte en una fecha pasada las dejaría a todas fuera del ciclo —
     * que es precisamente lo que comprueba {@link #laPosteriorEspera()}. Es el día del reloj de negocio (Ciudad de
     * México), el mismo en que se fechan las compras: con el de la máquina, que va una hora atrás, entre las dos
     * medianoches las compras de hoy caían «después» del corte.
     */
    private static final LocalDate CORTE = BusinessClock.current().today();
    private static final LocalDate VENCE = CORTE.plusDays(15);

    @BeforeEach
    void init() {
        servicio = new CycleBillingService(accounts, dispositions, installments);
        ofTheLine.clear();
        cycleInstallments.clear();
    }

    private CreditAccount card() {
        CreditAccount c = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-TDC-1", UUID.randomUUID(),
                "CC-001", 1, "CREDIT_CARD", "REVOLVING",
                null, new BigDecimal("50000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.16"), "032180000118359719", "MEDIO", null, null);
        c.activate(BigDecimal.ZERO);
        return c;
    }

    private Disposition purchase(String amount, boolean revolvente) {
        Disposition d = Disposition.create(accountId, DispositionType.SELF_USE,
                new BigDecimal(amount), null, "evt-" + UUID.randomUUID());
        if (revolvente) d.asPureRevolving();
        ofTheLine.add(d);
        return d;
    }

    /** Lo ya facturado del ciclo, que es la guarda de idempotencia del corte. */
    private final List<Installment> cycleInstallments = new ArrayList<>();

    private void cutoff(CreditAccount account) {
        lenient().when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        lenient().when(dispositions.findByCreditAccountId(accountId)).thenReturn(ofTheLine);
        lenient().when(installments.findByScheduleIdOrdered(accountId)).thenReturn(cycleInstallments);
        lenient().doAnswer(inv -> cycleInstallments.addAll(inv.getArgument(0)))
                .when(installments).saveAll(any());
        servicio.onCutoffClosed(new CorteCerrado(accountId, 1, CORTE, VENCE, new BigDecimal("8000")));
    }

    private Installment generatedInstallment() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Installment>> captor = ArgumentCaptor.forClass(List.class);
        verify(installments).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().get(0);
    }

    @Test
    @DisplayName("el corte genera UNA cuota con la suma de las compras del ciclo")
    void sumaLasComprasDelCiclo() {
        purchase("3000", true);
        purchase("5000", true);

        cutoff(card());

        Installment installment = generatedInstallment();
        assertThat(installment.getPrincipalAmount()).isEqualByComparingTo("8000");
        // La fecha exigible de una revolvente sale del CORTE: no tiene plan de donde sacarla.
        assertThat(installment.getDueDate()).isEqualTo(VENCE);
        // Cuelga de la CUENTA, no de una compra: representa al ciclo entero.
        assertThat(installment.getScheduleId()).isEqualTo(accountId);
    }

    @Test
    @DisplayName("una compra DIFERIDA sale del exigible — la resta se hace sola")
    void laDiferidaSale() {
        purchase("3000", true);
        Disposition diferida = purchase("5000", true);
        diferida.defer(6);   // el titular la parcializa antes del corte

        cutoff(card());

        // 3 000 + cuota del plan de la diferida, que entra por su propio calendario. Aquí sólo
        // los 3 000: diferir la convirtió en AMORTIZED y dejó de ser revolvente.
        assertThat(generatedInstallment().getPrincipalAmount()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("la cuota del ciclo lleva SÓLO capital — el interés lo devenga charges aparte")
    void soloCapital() {
        purchase("3000", true);

        cutoff(card());

        // Meterlo también aquí lo cobraría dos veces. Y mantiene correcta la base del moratorio,
        // que es capital vencido (BK-19): con interés dentro, la mora se cobraría sobre interés.
        Installment installment = generatedInstallment();
        assertThat(installment.getInterestAmount()).isEqualByComparingTo("0");
        assertThat(installment.getTaxAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el corte siguiente NO vuelve a exigir lo ya facturado")
    void noSeFacturaDosVeces() {
        Disposition c1 = purchase("3000", true);
        cutoff(card());

        // `planMode` sigue siendo REVOLVING después de facturarla: sin la marca de ciclo, el corte
        // siguiente la volvería a cobrar.
        assertThat(c1.getBilledCycle()).isEqualTo(1);
        assertThat(c1.isDueAtCutoff()).isFalse();
    }

    @Test
    @DisplayName("🔑 el corte REENTREGADO con una compra rezagada no rompe la restricción única")
    void corteReentregadoConCompraRezagada() {
        purchase("3000", true);
        cutoff(card());
        org.mockito.Mockito.clearInvocations(installments);

        // Kafka entrega al menos una vez. Entretanto llega tarde el evento de otra compra con fecha
        // anterior al corte: sin la guarda, se intentaría una SEGUNDA cuota del mismo ciclo, que
        // choca contra `uq_installment_number` (schedule_id, número) y tumba el corte entero.
        purchase("1500", true);
        cutoff(card());

        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("la compra rezagada NO se pierde: queda para el corte siguiente")
    void laRezagadaEsperaAlSiguiente() {
        purchase("3000", true);
        cutoff(card());

        Disposition rezagada = purchase("1500", true);
        cutoff(card());

        // No se facturó en el ciclo cerrado, y sigue exigible: la recoge el corte que viene.
        assertThat(rezagada.getBilledCycle()).isNull();
        assertThat(rezagada.isDueAtCutoff()).isTrue();
    }

    @Test
    @DisplayName("una compra POSTERIOR al corte espera al ciclo siguiente")
    void laPosteriorEspera() {
        // `Disposition.create` la fecha con `Instant.now()`, así que es de hoy — muy posterior a un
        // corte de junio de 2026 sólo si el reloj lo dice. Se corta contra una fecha ya pasada.
        purchase("3000", true);
        CreditAccount t = card();
        given(accounts.findById(accountId)).willReturn(Optional.of(t));
        given(dispositions.findByCreditAccountId(accountId)).willReturn(ofTheLine);

        servicio.onCutoffClosed(new CorteCerrado(accountId, 1,
                LocalDate.of(2020, 1, 31), LocalDate.of(2020, 2, 15), BigDecimal.ZERO));

        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("un producto A PLAZO no recibe cuota de ciclo: ya la tiene en su plan")
    void aPlazoNoSeFactura() {
        CreditAccount withTerm = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-PL-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        withTerm.activate(new BigDecimal("50000"));
        given(accounts.findById(accountId)).willReturn(Optional.of(withTerm));

        servicio.onCutoffClosed(new CorteCerrado(accountId, 1, CORTE, VENCE, new BigDecimal("50000")));

        // Fabricarle otro exigible aquí sería cobrarle dos veces la misma cuota.
        verify(installments, never()).saveAll(any());
    }

    private final List<Installment> planInstallments = new ArrayList<>();

    private void cutoffWithMinimum(CreditAccount account, String balance, String minimum) {
        lenient().when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        lenient().when(dispositions.findByCreditAccountId(accountId)).thenReturn(ofTheLine);
        lenient().when(installments.findByScheduleIdOrdered(accountId)).thenReturn(cycleInstallments);
        lenient().when(installments.findByScheduleIds(any())).thenReturn(planInstallments);
        lenient().doAnswer(inv -> {
            List<Installment> saved = inv.getArgument(0);
            saved.stream().filter(i -> i.getScheduleId().equals(accountId)).forEach(cycleInstallments::add);
            return null;
        }).when(installments).saveAll(any());
        servicio.onCutoffClosed(new CorteCerrado(accountId, 1, CORTE, VENCE, new BigDecimal(balance), new BigDecimal(minimum)));
    }

    @Test
    @DisplayName("con pago mínimo, la cuota del ciclo es el mínimo: quien lo paga queda al corriente")
    void laCuotaDelCicloEsElMinimo() {
        purchase("8000", true);
        cutoffWithMinimum(card(), "8000", "400");

        assertThat(cycleInstallments).hasSize(1);
        Installment cuota = cycleInstallments.get(0);
        // The product asks 5 % (400), but a card's minimum has the Banxico floor: 1.25 % of its 50,000 line = 625.
        assertThat(cuota.getPrincipalAmount()).isEqualByComparingTo("625");
        assertThat(cuota.getDueDate()).isEqualTo(VENCE);
        assertThat(cuota.getBilledCycle()).isEqualTo(1);
    }

    @Test
    @DisplayName("las cuotas de los planes que vencen hasta la fecha límite las cobra el estado: vencen en su fecha límite")
    void lasCuotasDeLosPlanesLasCobraElEstado() {
        Disposition msi = purchase("6000", false);
        Installment primera = Installment.of(msi.getDispositionId(), 1, CORTE.plusDays(5), new BigDecimal("1000"), BigDecimal.ZERO);
        Installment segunda = Installment.of(msi.getDispositionId(), 2, CORTE.plusDays(35), new BigDecimal("1000"), BigDecimal.ZERO);
        planInstallments.addAll(List.of(primera, segunda));

        cutoffWithMinimum(card(), "9000", "450");

        assertThat(primera.getBilledCycle()).isEqualTo(1);
        assertThat(primera.getDueDate()).as("due on the statement's due date, not its own").isEqualTo(VENCE);
        assertThat(primera.getOriginalDueDate()).isEqualTo(CORTE.plusDays(5));
        assertThat(segunda.getBilledCycle()).as("after the due date: the next statement bills it").isNull();
        // The obligation: the minimum plus the month it bills (1,000). The product's 450 is below the card's Banxico
        // floor (1.25 % of the 50,000 line = 625), so the minimum is 625: 1,625.
        assertThat(cycleInstallments.get(0).getPrincipalAmount()).isEqualByComparingTo("1625");
    }

    @Test
    @DisplayName("sin saldo revolvente, la obligación del ciclo es la mensualidad que factura, no el 5 % del saldo")
    void sinRevolventeLaObligacionEsLaMensualidad() {
        Disposition plazo = purchase("200000", false);
        planInstallments.add(Installment.of(plazo.getDispositionId(), 1, CORTE.plusDays(1), new BigDecimal("18611.24"), BigDecimal.ZERO));
        planInstallments.add(Installment.of(plazo.getDispositionId(), 2, CORTE.plusDays(31), new BigDecimal("200000"), BigDecimal.ZERO));

        cutoffWithMinimum(card(), "203596", "10179.80");

        assertThat(cycleInstallments).singleElement()
                .satisfies(c -> assertThat(c.getPrincipalAmount()).isEqualByComparingTo("18611.24"));
        assertThat(planInstallments.get(0).getBilledCycle()).isEqualTo(1);
    }

    @Test
    @DisplayName("el corte reentregado no vuelve a facturar: una cuota ya cobrada por un estado no se mueve otra vez")
    void elCorteReentregadoNoRefactura() {
        Disposition msi = purchase("6000", false);
        Installment primera = Installment.of(msi.getDispositionId(), 1, CORTE.plusDays(5), new BigDecimal("1000"), BigDecimal.ZERO);
        planInstallments.add(primera);

        cutoffWithMinimum(card(), "9000", "450");
        cutoffWithMinimum(card(), "9000", "450");

        assertThat(cycleInstallments).as("one cycle installment, not two").hasSize(1);
        assertThat(primera.getDueDate()).isEqualTo(VENCE);
    }

    @Test
    @DisplayName("distribuidora: la obligación es el capital de las cuotas que factura más los cargos del periodo, aunque su ciclo traiga mínimo")
    void distribuidoraCapitalMasCargos() {
        CreditAccount linea = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-DIST-1", UUID.randomUUID(), "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("500000"), null, new BigDecimal("0.20"), new BigDecimal("0.30"),
                null, new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        linea.activate(BigDecimal.ZERO);
        Disposition colocacion = Disposition.create(accountId, DispositionType.THIRD_PARTY_CREDIT,
                new BigDecimal("20000"), UUID.randomUUID(), "evt-" + UUID.randomUUID());
        ofTheLine.add(colocacion);
        planInstallments.add(Installment.of(colocacion.getDispositionId(), 1, CORTE.plusDays(1),
                new BigDecimal("3259.74"), new BigDecimal("150.00"), new BigDecimal("28.47")));
        BalanceEventRepository ledger = mock(BalanceEventRepository.class);
        given(ledger.chargesBetween(any(), any(), any())).willReturn(new BigDecimal("496.30"));
        servicio = new CycleBillingService(accounts, dispositions, installments, ledger);

        lenient().when(accounts.findById(accountId)).thenReturn(Optional.of(linea));
        lenient().when(dispositions.findByCreditAccountId(accountId)).thenReturn(ofTheLine);
        lenient().when(installments.findByScheduleIdOrdered(accountId)).thenReturn(cycleInstallments);
        lenient().when(installments.findByScheduleIds(any())).thenReturn(planInstallments);
        lenient().doAnswer(inv -> {
            List<Installment> saved = inv.getArgument(0);
            saved.stream().filter(i -> i.getScheduleId().equals(accountId)).forEach(cycleInstallments::add);
            return null;
        }).when(installments).saveAll(any());
        servicio.onCutoffClosed(new CorteCerrado(accountId, 1, CORTE, VENCE, new BigDecimal("20496.30"),
                new BigDecimal("1024.82"), CORTE.minusDays(14)));

        assertThat(cycleInstallments).singleElement()
                .satisfies(c -> assertThat(c.getPrincipalAmount()).isEqualByComparingTo("3756.04"));
    }

    /** The distributor line's cycle: cuts on the 6th and the 21st, paid by the 18th and the 3rd. */
    private static final BillingCycle QUINCENA = new BillingCycle("SEMI_MONTHLY", null, 0, 2, null, "NEXT",
            "PER_PERIOD", List.of(6, 21), List.of(18, 3));

    /** A distributor line on the fortnight, its cutoff closed with the period's charges. */
    private void distributorCutoff(int ciclo, LocalDate corte, LocalDate vence, String cargos) {
        CreditAccount linea = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-DIST-2", UUID.randomUUID(), "DL-001", 1, "DISTRIBUTOR_LINE", "REVOLVING",
                null, new BigDecimal("150000"), null, new BigDecimal("0.95"), new BigDecimal("0.36"),
                null, new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        linea.activate(BigDecimal.ZERO);
        BalanceEventRepository ledger = mock(BalanceEventRepository.class);
        given(ledger.chargesBetween(any(), any(), any())).willReturn(new BigDecimal(cargos));
        ProductConfigVersion config = mock(ProductConfigVersion.class);
        given(config.getBillingCycle()).willReturn(QUINCENA);
        ProductConfigResolver configs = mock(ProductConfigResolver.class);
        given(configs.resolveForAccount(any(), any())).willReturn(Optional.of(config));
        servicio = new CycleBillingService(accounts, dispositions, installments, ledger, configs);

        lenient().when(accounts.findById(accountId)).thenReturn(Optional.of(linea));
        lenient().when(dispositions.findByCreditAccountId(accountId)).thenReturn(ofTheLine);
        lenient().when(installments.findByScheduleIdOrdered(accountId)).thenReturn(cycleInstallments);
        lenient().when(installments.findByScheduleIds(any())).thenReturn(planInstallments);
        lenient().doAnswer(inv -> {
            List<Installment> saved = inv.getArgument(0);
            saved.stream().filter(i -> i.getScheduleId().equals(accountId)).forEach(cycleInstallments::add);
            return null;
        }).when(installments).saveAll(any());
        servicio.onCutoffClosed(new CorteCerrado(accountId, ciclo, corte, vence, new BigDecimal("8000"), null,
                QUINCENA.semiMonthly().previousCutoff(corte).plusDays(1)));
    }

    @Test
    @DisplayName("Semana Santa: el estado del 6 de abril vence el 21, día del corte siguiente, y no cobra las cuotas de ese corte")
    void holyWeekDoesNotBillTheNextCutoffsInstallments() {
        Disposition colocacion = Disposition.create(accountId, DispositionType.THIRD_PARTY_CREDIT,
                new BigDecimal("10000"), UUID.randomUUID(), "evt-" + UUID.randomUUID());
        ofTheLine.add(colocacion);
        // Installments 5 and 6 of a 10,000 placement, dated on their cutoffs (6 and 21 April 2025).
        Installment quinta = Installment.of(colocacion.getDispositionId(), 5, LocalDate.of(2025, 4, 6),
                new BigDecimal("446.27"), new BigDecimal("316.67"), new BigDecimal("50.67"));
        Installment sexta = Installment.of(colocacion.getDispositionId(), 6, LocalDate.of(2025, 4, 21),
                new BigDecimal("460.40"), new BigDecimal("302.54"), new BigDecimal("48.41"));
        planInstallments.addAll(List.of(quinta, sexta));

        // The 6 April statement is paid by the 21st: the 18th is Good Friday, then a weekend.
        distributorCutoff(7, LocalDate.of(2025, 4, 6), LocalDate.of(2025, 4, 21), "367.34");

        assertThat(quinta.getBilledCycle()).isEqualTo(7);
        assertThat(quinta.getDueDate()).isEqualTo(LocalDate.of(2025, 4, 21));
        assertThat(sexta.getBilledCycle()).as("the 21 April installment is the next statement's").isNull();
        // What the statement asks for: the fifth installment, its principal plus the period's interest and IVA.
        assertThat(cycleInstallments).singleElement()
                .satisfies(c -> assertThat(c.getPrincipalAmount()).isEqualByComparingTo("813.61"));
    }

    @Test
    @DisplayName("Semana Santa: el corte del 21 de abril cobra su cuota y la manda al 5 de mayo")
    void theNextCutoffBillsItsOwnInstallment() {
        Disposition colocacion = Disposition.create(accountId, DispositionType.THIRD_PARTY_CREDIT,
                new BigDecimal("10000"), UUID.randomUUID(), "evt-" + UUID.randomUUID());
        ofTheLine.add(colocacion);
        Installment sexta = Installment.of(colocacion.getDispositionId(), 6, LocalDate.of(2025, 4, 21),
                new BigDecimal("460.40"), new BigDecimal("302.54"), new BigDecimal("48.41"));
        planInstallments.add(sexta);

        distributorCutoff(8, LocalDate.of(2025, 4, 21), LocalDate.of(2025, 5, 5), "350.95");

        assertThat(sexta.getBilledCycle()).isEqualTo(8);
        assertThat(sexta.getDueDate()).isEqualTo(LocalDate.of(2025, 5, 5));
        assertThat(cycleInstallments).singleElement()
                .satisfies(c -> assertThat(c.getPrincipalAmount()).isEqualByComparingTo("811.35"));
    }

    @Test
    @DisplayName("diferir dos veces la misma compra se rechaza")
    void noSeDifiereDosVeces() {
        Disposition d = purchase("5000", true);
        d.defer(6);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.defer(12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no se difiere dos veces");
    }
}
