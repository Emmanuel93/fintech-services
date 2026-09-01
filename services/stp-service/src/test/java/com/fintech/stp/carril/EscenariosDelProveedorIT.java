package com.fintech.stp.carril;

import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.infrastructure.adapter.out.http.stub.StubScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los escenarios del proveedor, provocados a propósito.
 *
 * <p><b>Este es el motivo por el que no hay {@code Noop} en ninguna parte.</b> Un no-op confirma
 * siempre; un mock que se comporta como el proveedor <b>también falla</b>, y esa es la mitad del
 * comportamiento que hay que poder ejercitar. Los escenarios se eligen por los centavos del importe,
 * así que provocar un rechazo de PLD es cambiar dos dígitos.
 *
 * <p>Se prueba el <b>registro</b>, que es donde el proveedor acepta o rechaza. La liquidación
 * posterior la maneja el poller, que tiene su propio ciclo.
 */
@SpringBootTest
@ActiveProfiles("test")
class EscenariosDelProveedorIT extends AbstractIntegrationTest {

    @Override protected List<String> esquemas() { return List.of("stp"); }

    @Autowired RegisterPaymentOrderUseCase registrar;
    @Autowired StpPaymentOrderRepository ordenes;
    @Autowired StpCompanyRepository empresas;

    private UUID companyId;

    @BeforeEach
    void alta() {
        companyId = empresas.save(StpCompany.create(
                "DEMO-" + UUID.randomUUID().toString().substring(0, 8), "FINTECH_SA",
                90646, "CR", "646", "180", "0000")).getCompanyId();
    }

    /** Los centavos eligen el escenario: {@code .00} liquida, {@code .02} rechaza por PLD. */
    private StpPaymentOrder registrarCon(StubScenario escenario) {
        UUID peticion = UUID.randomUUID();
        BigDecimal monto = new BigDecimal("1000").add(
                new BigDecimal(escenario.ordinal()).movePointLeft(2));

        registrar.register(new RegisterPaymentOrderCommand(peticion, companyId, monto, "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                "DISPOSICION", 1L, null, "corr-1",
                UUID.randomUUID(), "646180000000000012", "FINTECH SA DE CV", "FSE200101AB1",
                "CLI-001"));

        return ordenes.findByPaymentRequestId(peticion).orElseThrow();
    }

    @Test
    @DisplayName("el escenario se elige por los CENTAVOS del importe")
    void losCentavosEligen() {
        // Es lo que hace barato provocar un caso raro: cambiar dos dígitos, no montar un servidor.
        assertThat(StubScenario.forAmount(new BigDecimal("1000.00"))).isEqualTo(StubScenario.SETTLED);
        assertThat(StubScenario.forAmount(new BigDecimal("1000.02")))
                .isEqualTo(StubScenario.REJECTED_PLD);
        assertThat(StubScenario.forAmount(new BigDecimal("1000.06")))
                .isEqualTo(StubScenario.NEVER_SETTLES);
    }

    @Test
    @DisplayName("una orden que el proveedor va a liquidar queda registrada y pendiente")
    void ordenQueLiquida() {
        StpPaymentOrder o = registrarCon(StubScenario.SETTLED);

        // Registrar no es liquidar: la orden nace PENDING y el relay la manda. Confundir las dos
        // cosas es exactamente lo que hacía el stub que se quitó de cartera.
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getTrackingKey()).isNotBlank();
    }

    @Test
    @DisplayName("un escenario de rechazo se registra igual: el rechazo llega al despachar")
    void elRechazoNoOcurreAlRegistrar() {
        StpPaymentOrder o = registrarCon(StubScenario.REJECTED_PLD);

        // El registro local siempre ocurre; quien habla con el proveedor es el relay. Sin la orden
        // persistida no habría dónde anotar el rechazo cuando llegue.
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getAmount()).isEqualByComparingTo("1000.02");
    }

    @Test
    @DisplayName("cada orden lleva su propia clave de rastreo, aunque sean del mismo día y empresa")
    void clavesDistintas() {
        StpPaymentOrder a = registrarCon(StubScenario.SETTLED);
        StpPaymentOrder b = registrarCon(StubScenario.RETURNED);

        // La clave es lo que el banco reporta y con lo que se concilia: dos órdenes con la misma
        // clave harían imposible el cruce determinista.
        assertThat(a.getTrackingKey()).isNotEqualTo(b.getTrackingKey());
    }

    @Test
    @DisplayName("el nombre del beneficiario se guarda completo Y truncado a lo que se firma")
    void nombreCompletoYFirmado() {
        StpPaymentOrder o = registrarCon(StubScenario.SETTLED);

        // El legado guardaba sólo el completo y firmaba el truncado, lo que rompía después la
        // comparación de nombres contra el CEP.
        assertThat(o.getBeneficiaryName()).isEqualTo("JUAN PEREZ");
        assertThat(o.getBeneficiaryNameSent()).isNotNull();
    }
}
