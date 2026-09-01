package com.fintech.creditportfolio.skip;

import com.fintech.creditportfolio.application.port.in.SkipPaymentUseCase.SaltarPago;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.SkipPaymentService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.application.port.in.SkipPaymentUseCase.NoSePuedeSaltarException;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.OpcionesDePago;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Saltar un pago: qué se mueve, qué no, y por qué no genera mora.
 *
 * <p>Este servicio no tenía prueba propia — sólo la del BFF, que lo moquea—, así que sus reglas
 * vivían sin red: el tope por ciclo, que la cola se corra detrás, y sobre todo <b>el invariante que
 * da sentido a la función</b>.
 *
 * <p>Ese invariante es el interesante, y se cumple <em>por construcción</em>: la consulta de cuotas
 * vencidas no mira si la cuota se saltó —filtra por {@code dueDate &lt; hoy}— y no le hace falta,
 * porque saltar <b>corre la fecha</b>. La cuota sale de «vencida» sola. Sin correr la fecha, saltar
 * un pago produciría exactamente la mora que pretende evitar.
 *
 * <p>Que se cumpla por construcción es elegante y también frágil: nada impide que alguien «mejore»
 * la consulta o el salto sin ver la relación. Estas pruebas la fijan.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaltarUnPagoTest {

    @Mock CreditAccountRepository accounts;
    @Mock InstallmentRepository installments;
    @Mock DispositionRepository dispositions;
    @Mock ProductConfigResolver configResolver;

    private SkipPaymentService servicio;
    private CreditAccount cuenta;
    private List<Installment> calendario;

    private static final LocalDate HOY = LocalDate.of(2026, 9, 15);

    @BeforeEach
    void preparar() {
        servicio = new SkipPaymentService(accounts, installments, dispositions, configResolver);

        cuenta = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-1", UUID.randomUUID(),
                "PL-IND-STD-V1", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("12000"), null, 6,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        cuenta.activate(new BigDecimal("12000"));

        UUID scheduleId = cuenta.getCreditAccountId();
        calendario = new ArrayList<>();
        // Seis cuotas mensuales desde octubre. `plusMonths` y no aritmética sobre el mes: sumarle
        // seis a agosto da el mes 14, que no existe.
        LocalDate primera = LocalDate.of(2026, 9, 15);
        for (int n = 1; n <= 6; n++) {
            calendario.add(Installment.of(scheduleId, n, primera.plusMonths(n),
                    new BigDecimal("2000"), new BigDecimal("100")));
        }

        given(accounts.findById(any())).willReturn(Optional.of(cuenta));
        given(installments.findByScheduleIdOrdered(any())).willReturn(calendario);
        given(dispositions.findByCreditAccountId(any())).willReturn(List.of());
        given(configResolver.resolveForAccount(any(), any())).willReturn(Optional.of(configCon("GIFT", 1)));
    }

    // ── El invariante: saltar no genera mora ─────────────────────────────────

    @Test
    @DisplayName("🔑 La cuota saltada deja de estar vencida — que es para lo que existe la función")
    void la_saltada_deja_de_estar_vencida() {
        Installment segunda = calendario.get(1);
        LocalDate vencimientoOriginal = segunda.getDueDate();
        LocalDate cincoDiasDespues = vencimientoOriginal.plusDays(5);

        // Antes de saltar: el envejecido la encontraría vencida ese día.
        assertThat(segunda.getDueDate()).isBefore(cincoDiasDespues);

        servicio.saltar(new SaltarPago(cuenta.getCreditAccountId(), segunda.getInstallmentId()));

        // Después: su vencimiento se corrió un período, así que el mismo día ya no la alcanza.
        // La consulta de vencidas filtra por fecha y no por la marca de salto — y no le hace falta.
        assertThat(segunda.getDueDate()).isAfter(cincoDiasDespues);
        assertThat(segunda.getOriginalDueDate()).isEqualTo(vencimientoOriginal);
    }

    @Test
    @DisplayName("La cola se corre detrás: saltar no amontona dos vencimientos el mismo día")
    void la_cola_se_corre_detras() {
        List<LocalDate> antes = calendario.stream().map(Installment::getDueDate).toList();

        servicio.saltar(new SaltarPago(cuenta.getCreditAccountId(), calendario.get(1).getInstallmentId()));

        // La primera no se toca; de la segunda en adelante, todas un período más allá.
        assertThat(calendario.get(0).getDueDate()).isEqualTo(antes.get(0));
        for (int n = 1; n < 6; n++) {
            assertThat(calendario.get(n).getDueDate()).isEqualTo(antes.get(n).plusMonths(1));
        }
    }

    // ── El modo lo decide el producto ────────────────────────────────────────

    @Test
    @DisplayName("El modo del producto queda sellado en la cuota: GIFT no devenga, DEFERRAL sí")
    void el_modo_queda_sellado() {
        given(configResolver.resolveForAccount(any(), any())).willReturn(Optional.of(configCon("DEFERRAL", 1)));

        servicio.saltar(new SaltarPago(cuenta.getCreditAccountId(), calendario.get(0).getInstallmentId()));

        // Quien devengue después lee esto para decidir. Dejarlo a elección del cliente convertiría
        // una recompensa en una forma de dejar de pagar intereses.
        assertThat(calendario.get(0).getSkippedMode()).isEqualTo("DEFERRAL");
        assertThat(calendario.get(0).getSkippedAt()).isNotNull();
    }

    // ── Los noes ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Un tope que no se aplica no es un tope")
    void el_tope_por_ciclo_se_aplica() {
        servicio.saltar(new SaltarPago(cuenta.getCreditAccountId(), calendario.get(0).getInstallmentId()));

        assertThatThrownBy(() -> servicio.saltar(
                new SaltarPago(cuenta.getCreditAccountId(), calendario.get(1).getInstallmentId())))
                .isInstanceOf(NoSePuedeSaltarException.class)
                .hasMessageContaining("saltos que permite el producto");
    }

    @Test
    @DisplayName("Un producto que no ofrece el salto lo rechaza con su motivo")
    void el_producto_que_no_lo_ofrece_lo_rechaza() {
        given(configResolver.resolveForAccount(any(), any()))
                .willReturn(Optional.of(configSinSalto()));

        assertThatThrownBy(() -> servicio.saltar(
                new SaltarPago(cuenta.getCreditAccountId(), calendario.get(0).getInstallmentId())))
                .isInstanceOf(NoSePuedeSaltarException.class)
                .hasMessageContaining("no admite saltar pagos");
    }

    @Test
    @DisplayName("Una cuota de otra cuenta no se salta desde aquí")
    void una_cuota_ajena_no_se_salta() {
        assertThatThrownBy(() -> servicio.saltar(
                new SaltarPago(cuenta.getCreditAccountId(), UUID.randomUUID())))
                .isInstanceOf(NoSePuedeSaltarException.class)
                .hasMessageContaining("no es de esta cuenta");
    }

    @Test
    @DisplayName("La misma cuota no se salta dos veces: correría dos períodos y el tope dejaría de significar nada")
    void la_misma_cuota_no_se_salta_dos_veces() {
        given(configResolver.resolveForAccount(any(), any())).willReturn(Optional.of(configCon("GIFT", 5)));
        Installment primera = calendario.get(0);

        servicio.saltar(new SaltarPago(cuenta.getCreditAccountId(), primera.getInstallmentId()));

        assertThatThrownBy(() -> servicio.saltar(
                new SaltarPago(cuenta.getCreditAccountId(), primera.getInstallmentId())))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private static ProductConfigVersion configCon(String modo, int tope) {
        return config(new OpcionesDePago("NONE", "NONE", null, null, null,
                false, null, true, tope, modo, true, List.of()));
    }

    private static ProductConfigVersion configSinSalto() {
        return config(new OpcionesDePago("NONE", "NONE", null, null, null,
                false, null, false, 0, null, true, List.of()));
    }

    private static ProductConfigVersion config(OpcionesDePago opciones) {
        Capabilities caps = new Capabilities(
                true, false, false, "SELF_USE", false, false, false, false, false, opciones);
        return ProductConfigVersion.of(
                "PL-IND-STD-V1", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C", caps,
                "FRENCH", "MONTHLY", null, null, null, null, "ACTIVE", false);
    }
}
