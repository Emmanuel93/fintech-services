package com.fintech.stp.rail;

import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.out.OutboxMessageRepository;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.infrastructure.adapter.in.api.TestSupportController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
class PaymentOrderRegistrationIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("stp"); }
    /** The bank calendar is a catalog seeded by Liquibase (015); truncating it would date orders on holidays. */
    @Override protected List<String> preserve() { return List.of("business_calendar_days"); }

    @Autowired RegisterPaymentOrderUseCase registrar;
    @Autowired StpPaymentOrderRepository ordenes;
    @Autowired StpCompanyRepository companies;
    @Autowired BusinessClock businessClock;
    @Autowired OutboxMessageRepository outbox;
    @Autowired ApplicationContext context;

    private UUID companyId;

    private static final String CLABE_ORDENANTE = "646180000000000012";

    @BeforeEach
    void register() {
        StpCompany company = companies.save(StpCompany.create(
                "DEMO-" + UUID.randomUUID().toString().substring(0, 8), "FINTECH_SA",
                90646, "CR", "646", "180", "0000"));
        companyId = company.getCompanyId();
    }

    private RegisterPaymentOrderCommand order(UUID request, String amount, String clabeOrdenante) {
        return new RegisterPaymentOrderCommand(request, companyId, new BigDecimal(amount), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                "DISPOSICION", 1L, null, "corr-1",
                UUID.randomUUID(), clabeOrdenante, "FINTECH SA DE CV", "FSE200101AB1", "CLI-001");
    }

    @Test
    @DisplayName("BK-07 · la orden CONGELA la cuenta ordenante que le mandaron")
    void congelaLaCuentaOrdenante() {
        UUID request = UUID.randomUUID();

        registrar.register(order(request, "20000.00", CLABE_ORDENANTE));

        StpPaymentOrder o = ordenes.findByPaymentRequestId(request).orElseThrow();
        // La fotografía, no un id que apunta al catálogo de hoy: entre registrar y firmar pueden
        // pasar minutos, y cambiar la cuenta de la empresa en ese hueco alteraba la cadena original
        // de una orden ya registrada.
        assertThat(o.getOrderingClabe()).isEqualTo(CLABE_ORDENANTE);
        assertThat(o.getOrderingHolderName()).isEqualTo("FINTECH SA DE CV");
        assertThat(o.hasRegisteredOrderingAccount()).isTrue();
    }

    @Test
    @DisplayName("la clave de rastreo se asigna con el prefijo de la empresa")
    void asignaClaveDeRastreo() {
        UUID request = UUID.randomUUID();

        registrar.register(order(request, "5000.00", CLABE_ORDENANTE));

        StpPaymentOrder o = ordenes.findByPaymentRequestId(request).orElseThrow();
        assertThat(o.getTrackingKey()).isNotBlank().startsWith("CR");
    }

    @Test
    @DisplayName("la misma petición registrada dos veces deja UNA orden")
    void esIdempotente() {
        UUID request = UUID.randomUUID();

        registrar.register(order(request, "5000.00", CLABE_ORDENANTE));
        registrar.register(order(request, "5000.00", CLABE_ORDENANTE));

        // El conector promete idempotencia por paymentRequestId, y el orquestador cuenta con ella
        // para poder reintentar sin miedo: entrega al menos una vez, a propósito.
        Integer cuantas = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stp.payment_orders WHERE payment_request_id = ?",
                Integer.class, request);
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
        var withoutAccount = new RegisterPaymentOrderCommand(UUID.randomUUID(), companyId,
                new BigDecimal("1000.00"), "MXN", "JUAN PEREZ",
                "646180157000000004", "40", null, 646, "X", 1L, null, "corr",
                null, null, null, null, null);

        assertThatThrownBy(() -> registrar.register(withoutAccount))
                .hasMessageContaining("no trae cuenta ordenante")
                .hasMessageContaining("la decide tesorería");
    }

    @Test
    @DisplayName("la orden toma la fecha de operación SPEI: corte de las 18:00, inhábiles y retención en la ventana")
    void dayOfOperationFollowsTheSpeiRule() {
        record Case(LocalDateTime at, LocalDate expected, boolean held) {}
        List<Case> cases = List.of(
                new Case(LocalDateTime.of(2026, 11, 30, 10, 0), LocalDate.of(2026, 11, 30), false),
                new Case(LocalDateTime.of(2026, 11, 30, 18, 30), LocalDate.of(2026, 12, 1), false),
                new Case(LocalDateTime.of(2026, 11, 30, 17, 55), LocalDate.of(2026, 12, 1), true),
                new Case(LocalDateTime.of(2026, 4, 2, 10, 0), LocalDate.of(2026, 4, 6), false),     // Jueves Santo
                new Case(LocalDateTime.of(2026, 10, 31, 11, 0), LocalDate.of(2026, 11, 3), false)); // sábado, 2-nov inhábil
        try {
            for (Case c : cases) {
                businessClock.shiftTo(c.at());
                UUID request = UUID.randomUUID();
                registrar.register(order(request, "7000.00", CLABE_ORDENANTE));

                StpPaymentOrder o = ordenes.findByPaymentRequestId(request).orElseThrow();
                assertThat(o.getBusinessDate()).as("fecha de operación de una orden de %s", c.at()).isEqualTo(c.expected());
                assertThat(o.getTrackingKey()).contains(c.expected().format(DateTimeFormatter.BASIC_ISO_DATE));
                Instant notBefore = outbox.findByAggregateId(o.getStpPaymentOrderId()).get(0).getNextAttemptAt();
                if (c.held()) {
                    assertThat(notBefore).as("retenida hasta las 18:10")
                            .isEqualTo(c.at().toLocalDate().atTime(18, 10).atZone(businessClock.zone()).toInstant());
                } else {
                    assertThat(notBefore).as("sale de inmediato").isBeforeOrEqualTo(businessClock.now());
                }
            }

            // Performance: registering stays cheap with the rule and the calendar in the way.
            businessClock.shiftTo(LocalDateTime.of(2026, 11, 30, 10, 0));
            long started = System.nanoTime();
            for (int i = 0; i < 50; i++) {
                registrar.register(order(UUID.randomUUID(), "100.00", CLABE_ORDENANTE));
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            assertThat(ms).as("50 órdenes registradas en %d ms", ms).isLessThan(10_000);
        } finally {
            businessClock.resetShift();
        }
    }

    @Test
    @DisplayName("sin soporte de pruebas no existe la consulta de liquidaciones a pedido")
    void noTestSupportWithoutTheFlag() {
        assertThat(context.getBeansOfType(TestSupportController.class)).isEmpty();
    }
}
