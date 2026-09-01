package com.fintech.creditportfolio.revolvente;

import com.fintech.creditportfolio.application.port.in.DeferDispositionUseCase.DiferirCompra;
import com.fintech.creditportfolio.application.port.in.DeferDispositionUseCase.NoSePuedeDiferirException;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.application.service.DispositionDeferralService;
import com.fintech.creditportfolio.application.service.ProductConfigResolver;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionPlanMode;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.config.Capabilities;
import com.fintech.creditportfolio.domain.config.OpcionesDePago;
import com.fintech.creditportfolio.domain.config.OpcionesDePago.BandaDeDiferimiento;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Diferir una compra ya hecha — la mecánica de la tarjeta, opuesta a la del distribuidor.
 *
 * <p>El distribuidor fija el plazo <b>al</b> colocar. Aquí la compra ya ocurrió y el titular decide
 * después, antes de que corte el ciclo.
 */
@ExtendWith(MockitoExtension.class)
class DiferirCompraTest {

    @Mock CreditAccountRepository accounts;
    @Mock DispositionRepository dispositions;
    @Mock InstallmentRepository installments;
    @Mock ProductConfigResolver configResolver;
    @Mock CreditPortfolioEventPublisher eventPublisher;

    DispositionDeferralService servicio;

    private CreditAccount cuenta;
    private Disposition compra;

    /** 3-6 sin intereses, 7-12 al 24 %. Lo que un producto de tarjeta ofrece de verdad. */
    private static OpcionesDePago conMsi() {
        return new OpcionesDePago("POST_HOC", "BEFORE_CUTOFF", 3, 3, 12,
                false, null, false, 0, "DEFERRAL", false,
                List.of(new BandaDeDiferimiento(3, 6, BigDecimal.ZERO),
                        new BandaDeDiferimiento(7, 12, new BigDecimal("0.2400"))));
    }

    private static ProductConfigVersion config(OpcionesDePago opciones) {
        return ProductConfigVersion.of("CC-001", 1, "CREDIT_CARD", "REVOLVING", "B2C",
                new Capabilities(false, true, true, "SELF_USE", true, true, false, false, false,
                        opciones),
                "FRENCH", "MONTHLY", 1000,
                new BigDecimal("0.36"), new BigDecimal("0.54"), new BigDecimal("2.0"),
                "ACTIVE", false);
    }

    @BeforeEach
    void init() {
        servicio = new DispositionDeferralService(accounts, dispositions, installments,
                configResolver, new AmortizationEngine(), eventPublisher);

        cuenta = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-TDC-1", UUID.randomUUID(),
                "CC-001", 1, "CREDIT_CARD", "REVOLVING",
                null, new BigDecimal("50000"), null,
                new BigDecimal("0.36"), new BigDecimal("0.54"),
                null, new BigDecimal("0.16"), "032180000118359719", "MEDIO", null, null);
        cuenta.activate(BigDecimal.ZERO);

        compra = Disposition.create(cuenta.getCreditAccountId(), DispositionType.SELF_USE,
                new BigDecimal("6000.00"), null, "evt-1").comoRevolventePura();

        lenient().when(accounts.findById(any())).thenReturn(Optional.of(cuenta));
        lenient().when(dispositions.findById(any())).thenReturn(Optional.of(compra));
        lenient().when(configResolver.resolveForAccount(any(), any()))
                .thenReturn(Optional.of(config(conMsi())));
    }

    private void diferir(Integer plazo) {
        servicio.diferir(new DiferirCompra(
                cuenta.getCreditAccountId(), compra.getDispositionId(), plazo));
    }

    @SuppressWarnings("unchecked")
    private List<Installment> planGenerado() {
        ArgumentCaptor<List<Installment>> captor = ArgumentCaptor.forClass(List.class);
        verify(installments).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("diferir a 6 MSI genera un plan de puro capital y saca la compra del corte")
    void seisMsi() {
        diferir(6);

        List<Installment> plan = planGenerado();
        assertThat(plan).hasSize(6);
        assertThat(plan).allSatisfy(c -> assertThat(c.getInterestAmount()).isEqualByComparingTo("0"));

        // La resta del exigible sale sola: ya no es REVOLVING, así que ya no suma en el corte.
        assertThat(compra.getPlanMode()).isEqualTo(DispositionPlanMode.AMORTIZED);
        assertThat(compra.esExigibleEnElCorte()).isFalse();
        // El calendario cuelga de la COMPRA, no de la cuenta.
        assertThat(plan.get(0).getScheduleId()).isEqualTo(compra.getDispositionId());
    }

    @Test
    @DisplayName("diferir a 12 SÍ devenga interés — la banda manda")
    void doceConTasa() {
        diferir(12);

        assertThat(planGenerado().get(0).getInterestAmount()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("sin plazo cae al DEFAULT del producto, que es 3")
    void plazoPorDefecto() {
        diferir(null);

        assertThat(planGenerado()).hasSize(3);
    }

    @Test
    @DisplayName("un plazo fuera del min/max del producto se rechaza")
    void plazoFueraDeRango() {
        assertThatThrownBy(() -> diferir(24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("máximo");
        verify(installments, never()).saveAll(any());
    }

    @Test
    @DisplayName("un producto que NO difiere rechaza la operación")
    void productoQueNoDifiere() {
        given(configResolver.resolveForAccount(any(), any()))
                .willReturn(Optional.of(config(OpcionesDePago.ninguna())));

        assertThatThrownBy(() -> diferir(6))
                .isInstanceOf(NoSePuedeDiferirException.class)
                .hasMessageContaining("no admite diferir");
    }

    @Test
    @DisplayName("una compra YA FACTURADA en un corte no se puede diferir")
    void yaFacturada() {
        // Diferirla entonces sería mover una deuda que el cliente ya debe.
        compra.facturarEnCiclo(1);

        assertThatThrownBy(() -> diferir(6))
                .isInstanceOf(NoSePuedeDiferirException.class)
                .hasMessageContaining("ciclo 1");
    }

    @Test
    @DisplayName("una compra de OTRA cuenta no se puede diferir con esta")
    void deOtraCuenta() {
        // Sin esta comprobación, cualquiera con un id de disposición difiere la compra de otro.
        Disposition ajena = Disposition.create(UUID.randomUUID(), DispositionType.SELF_USE,
                new BigDecimal("1000"), null, "evt-x").comoRevolventePura();
        given(dispositions.findById(any())).willReturn(Optional.of(ajena));

        assertThatThrownBy(() -> diferir(6))
                .isInstanceOf(NoSePuedeDiferirException.class)
                .hasMessageContaining("no pertenece a esta cuenta");
    }

    @Test
    @DisplayName("se publica la VENTANA en que fue revolvente — es lo que charges reversa")
    void publicaLaVentana() {
        diferir(6);

        ArgumentCaptor<DispositionDeferredEvent> captor =
                ArgumentCaptor.forClass(DispositionDeferredEvent.class);
        verify(eventPublisher).publishDispositionDeferred(captor.capture());

        DispositionDeferredEvent e = captor.getValue();
        assertThat(e.amount()).isEqualByComparingTo("6000.00");
        assertThat(e.nominalRate()).isEqualByComparingTo("0");
        assertThat(e.revolvingDesde()).isNotNull();
        assertThat(e.revolvingHasta()).isNotNull();
        assertThat(e.revolvingHasta()).isAfterOrEqualTo(e.revolvingDesde());
    }

    @Test
    @DisplayName("un plazo sin banda de tasa configurada se rechaza, NO cae a la de originación")
    void sinBandaSeRechaza() {
        // Diferir al 36 % una compra que el cliente creía a MSI es el error que esto impide.
        OpcionesDePago sinBandas = new OpcionesDePago("POST_HOC", "BEFORE_CUTOFF", 3, 3, 12,
                false, null, false, 0, "DEFERRAL", false, List.of());
        given(configResolver.resolveForAccount(any(), any()))
                .willReturn(Optional.of(config(sinBandas)));

        assertThatThrownBy(() -> diferir(6))
                .isInstanceOf(NoSePuedeDiferirException.class)
                .hasMessageContaining("no tiene tasa de diferimiento");
    }
}
