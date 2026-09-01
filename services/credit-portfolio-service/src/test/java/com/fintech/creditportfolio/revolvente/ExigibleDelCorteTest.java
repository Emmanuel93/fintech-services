package com.fintech.creditportfolio.revolvente;

import com.fintech.creditportfolio.application.port.in.CloseCutoffCycleUseCase.CorteCerrado;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.CycleBillingService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
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
class ExigibleDelCorteTest {

    @Mock CreditAccountRepository accounts;
    @Mock DispositionRepository dispositions;
    @Mock InstallmentRepository installments;

    CycleBillingService servicio;

    private final UUID cuentaId = UUID.randomUUID();
    private final List<Disposition> delaLinea = new ArrayList<>();

    /**
     * El corte se sella en la fecha de negocio del día. Las compras se crean con el reloj del
     * sistema, así que fijar el corte en una fecha pasada las dejaría a todas fuera del ciclo —
     * que es precisamente lo que comprueba {@link #laPosteriorEspera()}.
     */
    private static final LocalDate CORTE = LocalDate.now();
    private static final LocalDate VENCE = CORTE.plusDays(15);

    @BeforeEach
    void init() {
        servicio = new CycleBillingService(accounts, dispositions, installments);
        delaLinea.clear();
        cuotasDeCiclo.clear();
    }

    private CreditAccount tarjeta() {
        CreditAccount c = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-TDC-1", UUID.randomUUID(),
                "CC-001", 1, "CREDIT_CARD", "REVOLVING",
                null, new BigDecimal("50000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.16"), "032180000118359719", "MEDIO", null, null);
        c.activate(BigDecimal.ZERO);
        return c;
    }

    private Disposition compra(String monto, boolean revolvente) {
        Disposition d = Disposition.create(cuentaId, DispositionType.SELF_USE,
                new BigDecimal(monto), null, "evt-" + UUID.randomUUID());
        if (revolvente) d.comoRevolventePura();
        delaLinea.add(d);
        return d;
    }

    /** Lo ya facturado del ciclo, que es la guarda de idempotencia del corte. */
    private final List<Installment> cuotasDeCiclo = new ArrayList<>();

    private void corte(CreditAccount cuenta) {
        lenient().when(accounts.findById(cuentaId)).thenReturn(Optional.of(cuenta));
        lenient().when(dispositions.findByCreditAccountId(cuentaId)).thenReturn(delaLinea);
        lenient().when(installments.findByScheduleIdOrdered(cuentaId)).thenReturn(cuotasDeCiclo);
        lenient().doAnswer(inv -> cuotasDeCiclo.addAll(inv.getArgument(0)))
                .when(installments).saveAll(any());
        servicio.onCutoffClosed(new CorteCerrado(cuentaId, 1, CORTE, VENCE, new BigDecimal("8000")));
    }

    private Installment cuotaGenerada() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Installment>> captor = ArgumentCaptor.forClass(List.class);
        verify(installments).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().get(0);
    }

    @Test
    @DisplayName("el corte genera UNA cuota con la suma de las compras del ciclo")
    void sumaLasComprasDelCiclo() {
        compra("3000", true);
        compra("5000", true);

        corte(tarjeta());

        Installment cuota = cuotaGenerada();
        assertThat(cuota.getPrincipalAmount()).isEqualByComparingTo("8000");
        // La fecha exigible de una revolvente sale del CORTE: no tiene plan de donde sacarla.
        assertThat(cuota.getDueDate()).isEqualTo(VENCE);
        // Cuelga de la CUENTA, no de una compra: representa al ciclo entero.
        assertThat(cuota.getScheduleId()).isEqualTo(cuentaId);
    }

    @Test
    @DisplayName("una compra DIFERIDA sale del exigible — la resta se hace sola")
    void laDiferidaSale() {
        compra("3000", true);
        Disposition diferida = compra("5000", true);
        diferida.diferir(6);   // el titular la parcializa antes del corte

        corte(tarjeta());

        // 3 000 + cuota del plan de la diferida, que entra por su propio calendario. Aquí sólo
        // los 3 000: diferir la convirtió en AMORTIZED y dejó de ser revolvente.
        assertThat(cuotaGenerada().getPrincipalAmount()).isEqualByComparingTo("3000");
    }

    @Test
    @DisplayName("la cuota del ciclo lleva SÓLO capital — el interés lo devenga charges aparte")
    void soloCapital() {
        compra("3000", true);

        corte(tarjeta());

        // Meterlo también aquí lo cobraría dos veces. Y mantiene correcta la base del moratorio,
        // que es capital vencido (BK-19): con interés dentro, la mora se cobraría sobre interés.
        Installment cuota = cuotaGenerada();
        assertThat(cuota.getInterestAmount()).isEqualByComparingTo("0");
        assertThat(cuota.getTaxAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el corte siguiente NO vuelve a exigir lo ya facturado")
    void noSeFacturaDosVeces() {
        Disposition c1 = compra("3000", true);
        corte(tarjeta());

        // `planMode` sigue siendo REVOLVING después de facturarla: sin la marca de ciclo, el corte
        // siguiente la volvería a cobrar.
        assertThat(c1.getBilledCycle()).isEqualTo(1);
        assertThat(c1.esExigibleEnElCorte()).isFalse();
    }

    @Test
    @DisplayName("🔑 el corte REENTREGADO con una compra rezagada no rompe la restricción única")
    void corteReentregadoConCompraRezagada() {
        compra("3000", true);
        corte(tarjeta());
        org.mockito.Mockito.clearInvocations(installments);

        // Kafka entrega al menos una vez. Entretanto llega tarde el evento de otra compra con fecha
        // anterior al corte: sin la guarda, se intentaría una SEGUNDA cuota del mismo ciclo, que
        // choca contra `uq_installment_number` (schedule_id, número) y tumba el corte entero.
        compra("1500", true);
        corte(tarjeta());

        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("la compra rezagada NO se pierde: queda para el corte siguiente")
    void laRezagadaEsperaAlSiguiente() {
        compra("3000", true);
        corte(tarjeta());

        Disposition rezagada = compra("1500", true);
        corte(tarjeta());

        // No se facturó en el ciclo cerrado, y sigue exigible: la recoge el corte que viene.
        assertThat(rezagada.getBilledCycle()).isNull();
        assertThat(rezagada.esExigibleEnElCorte()).isTrue();
    }

    @Test
    @DisplayName("una compra POSTERIOR al corte espera al ciclo siguiente")
    void laPosteriorEspera() {
        // `Disposition.create` la fecha con `Instant.now()`, así que es de hoy — muy posterior a un
        // corte de junio de 2026 sólo si el reloj lo dice. Se corta contra una fecha ya pasada.
        compra("3000", true);
        CreditAccount t = tarjeta();
        given(accounts.findById(cuentaId)).willReturn(Optional.of(t));
        given(dispositions.findByCreditAccountId(cuentaId)).willReturn(delaLinea);

        servicio.onCutoffClosed(new CorteCerrado(cuentaId, 1,
                LocalDate.of(2020, 1, 31), LocalDate.of(2020, 2, 15), BigDecimal.ZERO));

        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("un producto A PLAZO no recibe cuota de ciclo: ya la tiene en su plan")
    void aPlazoNoSeFactura() {
        CreditAccount aPlazo = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-PL-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        aPlazo.activate(new BigDecimal("50000"));
        given(accounts.findById(cuentaId)).willReturn(Optional.of(aPlazo));

        servicio.onCutoffClosed(new CorteCerrado(cuentaId, 1, CORTE, VENCE, new BigDecimal("50000")));

        // Fabricarle otro exigible aquí sería cobrarle dos veces la misma cuota.
        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("diferir dos veces la misma compra se rechaza")
    void noSeDifiereDosVeces() {
        Disposition d = compra("5000", true);
        d.diferir(6);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> d.diferir(12))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no se difiere dos veces");
    }
}
