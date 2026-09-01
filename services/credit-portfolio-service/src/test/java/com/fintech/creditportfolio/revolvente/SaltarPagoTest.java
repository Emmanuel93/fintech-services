package com.fintech.creditportfolio.revolvente;

import com.fintech.creditportfolio.application.port.in.SkipPaymentUseCase.NoSePuedeSaltarException;
import com.fintech.creditportfolio.application.port.in.SkipPaymentUseCase.SaltarPago;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.application.service.SkipPaymentService;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.OpcionesDePago;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.lenient;

/**
 * Saltar un pago: se corre el compromiso y <b>no se genera mora</b>.
 *
 * <p>No existía nada. «Skip payment» no aparecía ni una vez en todo el monorepo — ni la palabra, ni
 * el concepto.
 */
@ExtendWith(MockitoExtension.class)
class SaltarPagoTest {

    @Mock CreditAccountRepository accounts;
    @Mock InstallmentRepository installments;
    @Mock DispositionRepository dispositions;
    @Mock ProductConfigResolver configResolver;

    SkipPaymentService servicio;

    private CreditAccount cuenta;
    private List<Installment> calendario;

    private static final LocalDate PRIMERA = LocalDate.of(2026, 7, 15);

    private static OpcionesDePago conSaltos(int tope, String modo) {
        return new OpcionesDePago("NONE", "NONE", 3, 1, 24,
                false, null, true, tope, modo, false, List.of());
    }

    private static ProductConfigVersion config(OpcionesDePago opciones) {
        return ProductConfigVersion.of("PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT", "B2C",
                new Capabilities(true, false, false, "SELF_USE", false, false, false, false, false,
                        opciones),
                "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.24"), new BigDecimal("0.36"), new BigDecimal("0.03"),
                "ACTIVE", false);
    }

    @BeforeEach
    void init() {
        servicio = new SkipPaymentService(accounts, installments, dispositions, configResolver);

        cuenta = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-PL-1", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("12000"), null, 6,
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                "FRENCH", new BigDecimal("0.16"), "032180000118359719", "BAJO", null, null);
        cuenta.activate(new BigDecimal("12000"));

        calendario = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            calendario.add(Installment.of(cuenta.getCreditAccountId(), n,
                    PRIMERA.plusMonths(n - 1L), new BigDecimal("2000"), new BigDecimal("200")));
        }

        lenient().when(accounts.findById(any())).thenReturn(Optional.of(cuenta));
        lenient().when(installments.findByScheduleIdOrdered(any())).thenReturn(calendario);
        lenient().when(configResolver.resolveForAccount(any(), any()))
                .thenReturn(Optional.of(config(conSaltos(1, "DEFERRAL"))));
    }

    private void saltar(int numeroDeCuota) {
        servicio.saltar(new SaltarPago(cuenta.getCreditAccountId(),
                calendario.get(numeroDeCuota - 1).getInstallmentId()));
    }

    @Test
    @DisplayName("la cuota saltada se corre un período y deja de vencer en su fecha original")
    void seCorreUnPeriodo() {
        saltar(2);

        Installment saltada = calendario.get(1);
        assertThat(saltada.seSalto()).isTrue();
        assertThat(saltada.getOriginalDueDate()).isEqualTo(PRIMERA.plusMonths(1));
        assertThat(saltada.getDueDate()).isAfter(saltada.getOriginalDueDate());
    }

    @Test
    @DisplayName("las cuotas de ATRÁS se corren también — si no, habría dos vencimientos el mismo día")
    void lasDeAtrasTambien() {
        LocalDate terceraOriginal = calendario.get(2).getDueDate();

        saltar(2);

        assertThat(calendario.get(2).getDueDate()).isAfter(terceraOriginal);
        // Pero NO cuentan como salto: no consumen el tope del cliente.
        assertThat(calendario.get(2).seSalto()).isFalse();
        assertThat(calendario.get(0).getDueDate()).isEqualTo(PRIMERA);   // la anterior no se mueve
    }

    @Test
    @DisplayName("el tope por ciclo se respeta")
    void elTopeSeRespeta() {
        saltar(2);

        // Un tope que no se aplica no es un tope.
        assertThatThrownBy(() -> saltar(3))
                .isInstanceOf(NoSePuedeSaltarException.class)
                .hasMessageContaining("Ya se usaron los 1 saltos");
    }

    @Test
    @DisplayName("un producto SIN saltos habilitados rechaza la operación")
    void productoSinSaltos() {
        given(configResolver.resolveForAccount(any(), any()))
                .willReturn(Optional.of(config(OpcionesDePago.ninguna())));

        assertThatThrownBy(() -> saltar(2))
                .isInstanceOf(NoSePuedeSaltarException.class)
                .hasMessageContaining("no admite saltar pagos");
    }

    @Test
    @DisplayName("el MODO lo decide el producto: GIFT no devenga, DEFERRAL sí")
    void elModoLoDecideElProducto() {
        given(configResolver.resolveForAccount(any(), any()))
                .willReturn(Optional.of(config(conSaltos(2, "GIFT"))));

        saltar(2);

        // Dejarlo a elección de quien pide convertiría una decisión de producto en una preferencia.
        assertThat(calendario.get(1).getSkippedMode()).isEqualTo("GIFT");
        assertThat(calendario.get(1).noDevenga()).isTrue();
    }

    @Test
    @DisplayName("con DEFERRAL el período SÍ devenga: sólo se aplaza")
    void deferralSiDevenga() {
        saltar(2);

        assertThat(calendario.get(1).getSkippedMode()).isEqualTo("DEFERRAL");
        assertThat(calendario.get(1).noDevenga()).isFalse();
    }

    @Test
    @DisplayName("una cuota YA PAGADA no se puede saltar")
    void yaPagada() {
        calendario.get(1).applyPayment(new BigDecimal("999999"));

        assertThatThrownBy(() -> saltar(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya está pagada");
    }

    @Test
    @DisplayName("saltar dos veces la MISMA cuota se rechaza")
    void dosVecesLaMisma() {
        given(configResolver.resolveForAccount(any(), any()))
                .willReturn(Optional.of(config(conSaltos(5, "DEFERRAL"))));
        saltar(2);

        // Sin esto, la correría dos períodos y el tope dejaría de significar nada.
        assertThatThrownBy(() -> saltar(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya se saltó");
    }

    @Test
    @DisplayName("🔑 en una TARJETA se puede saltar la cuota del CICLO, que es la que importa")
    void enTarjetaSeSaltaLaCuotaDelCiclo() {
        // El defecto: para una revolvente sólo se miraban los calendarios de las DISPOSICIONES, así
        // que un tarjetahabiente no encontraba su cuota de ciclo —la que genera el corte y cuelga de
        // la cuenta— y no podía saltar precisamente el pago que querría saltar. Lo único visible
        // eran los planes de compras que ya había diferido.
        CreditAccount tarjeta = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-TDC-1", UUID.randomUUID(),
                "PL-001", 1, "CREDIT_CARD", "REVOLVING",
                null, new BigDecimal("50000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.16"), "032180000118359719", "MEDIO", null, null);
        tarjeta.activate(BigDecimal.ZERO);

        Installment cuotaDelCiclo = Installment.of(tarjeta.getCreditAccountId(), 1,
                PRIMERA, new BigDecimal("8000"), BigDecimal.ZERO);

        given(accounts.findById(any())).willReturn(Optional.of(tarjeta));
        given(installments.findByScheduleIdOrdered(tarjeta.getCreditAccountId()))
                .willReturn(List.of(cuotaDelCiclo));
        given(dispositions.findByCreditAccountId(any())).willReturn(List.of());
        given(installments.findByScheduleIds(any())).willReturn(List.of());

        servicio.saltar(new SaltarPago(tarjeta.getCreditAccountId(),
                cuotaDelCiclo.getInstallmentId()));

        assertThat(cuotaDelCiclo.seSalto()).isTrue();
        assertThat(cuotaDelCiclo.getOriginalDueDate()).isEqualTo(PRIMERA);
    }

    @Test
    @DisplayName("una cuota de OTRA cuenta no se puede saltar")
    void deOtraCuenta() {
        assertThatThrownBy(() -> servicio.saltar(
                new SaltarPago(cuenta.getCreditAccountId(), UUID.randomUUID())))
                .isInstanceOf(NoSePuedeSaltarException.class)
                .hasMessageContaining("no es de esta cuenta");
    }
}
