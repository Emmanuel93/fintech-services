package com.fintech.stp.carril;

import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpPaymentOrder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El registro de una orden, con Spring y base reales.
 *
 * <p><b>Este servicio tenía ocho clases de prueba, todas de dominio puro.</b> El registro —que es
 * donde se decide con qué cuenta se firma, qué clave de rastreo se asigna y si la orden es
 * idempotente— no estaba probado con contexto.
 *
 * <p>Lo que se fija aquí es sobre todo BK-07: <b>la cuenta ordenante llega en la orden</b> y se
 * congela con ella. Antes se releía del catálogo local al momento de firmar, minutos después de
 * registrar, y cambiar la cuenta de una empresa en esa ventana alteraba la cadena original de una
 * orden ya registrada.
 */
@SpringBootTest
@ActiveProfiles("test")
class RegistroDeOrdenIT extends AbstractIntegrationTest {

    @Override protected List<String> esquemas() { return List.of("stp"); }

    @Autowired RegisterPaymentOrderUseCase registrar;
    @Autowired StpPaymentOrderRepository ordenes;
    @Autowired StpCompanyRepository empresas;

    private UUID companyId;

    private static final String CLABE_ORDENANTE = "646180000000000012";

    @BeforeEach
    void alta() {
        StpCompany empresa = empresas.save(StpCompany.create(
                "DEMO-" + UUID.randomUUID().toString().substring(0, 8), "FINTECH_SA",
                90646, "CR", "646", "180", "0000"));
        companyId = empresa.getCompanyId();
    }

    private RegisterPaymentOrderCommand orden(UUID peticion, String monto, String clabeOrdenante) {
        return new RegisterPaymentOrderCommand(peticion, companyId, new BigDecimal(monto), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                "DISPOSICION", 1L, null, "corr-1",
                UUID.randomUUID(), clabeOrdenante, "FINTECH SA DE CV", "FSE200101AB1", "CLI-001");
    }

    @Test
    @DisplayName("BK-07 · la orden CONGELA la cuenta ordenante que le mandaron")
    void congelaLaCuentaOrdenante() {
        UUID peticion = UUID.randomUUID();

        registrar.register(orden(peticion, "20000.00", CLABE_ORDENANTE));

        StpPaymentOrder o = ordenes.findByPaymentRequestId(peticion).orElseThrow();
        // La fotografía, no un id que apunta al catálogo de hoy: entre registrar y firmar pueden
        // pasar minutos, y cambiar la cuenta de la empresa en ese hueco alteraba la cadena original
        // de una orden ya registrada.
        assertThat(o.getOrderingClabe()).isEqualTo(CLABE_ORDENANTE);
        assertThat(o.getOrderingHolderName()).isEqualTo("FINTECH SA DE CV");
        assertThat(o.tieneCuentaOrdenanteRegistrada()).isTrue();
    }

    @Test
    @DisplayName("la clave de rastreo se asigna con el prefijo de la empresa")
    void asignaClaveDeRastreo() {
        UUID peticion = UUID.randomUUID();

        registrar.register(orden(peticion, "5000.00", CLABE_ORDENANTE));

        StpPaymentOrder o = ordenes.findByPaymentRequestId(peticion).orElseThrow();
        assertThat(o.getTrackingKey()).isNotBlank().startsWith("CR");
    }

    @Test
    @DisplayName("la misma petición registrada dos veces deja UNA orden")
    void esIdempotente() {
        UUID peticion = UUID.randomUUID();

        registrar.register(orden(peticion, "5000.00", CLABE_ORDENANTE));
        registrar.register(orden(peticion, "5000.00", CLABE_ORDENANTE));

        // El conector promete idempotencia por paymentRequestId, y el orquestador cuenta con ella
        // para poder reintentar sin miedo: entrega al menos una vez, a propósito.
        Integer cuantas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stp.payment_orders WHERE payment_request_id = ?",
                Integer.class, peticion);
        assertThat(cuantas).isEqualTo(1);
    }

    @Test
    @DisplayName("una CLABE de beneficiario inválida NO llega a viajar al proveedor")
    void clabeInvalidaNoViaja() {
        // DB-04 · un dígito verificador malo no merece un viaje a STP. Y rechazarlo aquí es lo que
        // permite que el rechazo trivial no dé la vuelta completa por Kafka.
        var mala = new RegisterPaymentOrderCommand(UUID.randomUUID(), companyId,
                new BigDecimal("1000.00"), "MXN", "JUAN PEREZ",
                "646180157000000005", "40", null, 646, "X", 1L, null, "corr",
                UUID.randomUUID(), CLABE_ORDENANTE, "FINTECH", null, "CLI-001");

        assertThatThrownBy(() -> registrar.register(mala))
                .hasMessageContaining("dígito verificador");
    }

    @Test
    @DisplayName("BK-07b · sin cuenta ordenante en la orden, se RECHAZA: este conector ya no elige")
    void sinCuentaOrdenanteSeRechaza() {
        // La caída al catálogo local se retiró con la tabla. Mantenerla habría dejado en pie
        // exactamente lo que este trabajo vino a quitar: un conector capaz de elegir por su cuenta
        // la cuenta de la que sale el dinero, con un `is_default` ciego al saldo y al costo.
        var sinCuenta = new RegisterPaymentOrderCommand(UUID.randomUUID(), companyId,
                new BigDecimal("1000.00"), "MXN", "JUAN PEREZ",
                "646180157000000004", "40", null, 646, "X", 1L, null, "corr",
                null, null, null, null, null);

        assertThatThrownBy(() -> registrar.register(sinCuenta))
                .hasMessageContaining("no trae cuenta ordenante")
                .hasMessageContaining("la decide tesorería");
    }
}
