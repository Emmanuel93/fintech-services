package com.fintech.stp.rail;

import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.ManageCompanyUseCase;
import com.fintech.stp.application.port.in.PollSettlementsUseCase;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.in.RelayOutboxUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpEventPublisher;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.BanxicoResponseCode;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.StpPaymentOrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What happens to an order when STP does not take it the plain way, driven through the stub's scenarios (the cents of
 * the amount choose them) with the real relay, poller, database and clock.
 *
 * <p>The demo of 2026-11-04 left three commission lots in flight for months: a -30 exhausted (D58), a timeout (D59) and
 * one accepted that never settles (SO-06, by design). The E2E never met them: its amounts never ended in those cents.
 * Here each outcome must end in a state the caller hears, without ever risking paying twice.
 */
@SpringBootTest
@ActiveProfiles("test")
class UnansweredOrdersIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("stp"); }
    /** The bank calendar is a catalog seeded by Liquibase (015); truncating it would date orders on holidays. */
    @Override protected List<String> preserve() { return List.of("business_calendar_days"); }

    /** A Tuesday with no holiday near: the day closes at 18:00 and the hold after it ends at 18:10. */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final String CLABE_ORDENANTE = "646180000000000012";

    @Autowired RegisterPaymentOrderUseCase registrar;
    @Autowired RelayOutboxUseCase relay;
    @Autowired PollSettlementsUseCase poller;
    @Autowired StpPaymentOrderRepository orders;
    @Autowired StpCompanyRepository companies;
    @Autowired ManageCompanyUseCase admin;
    @Autowired BusinessClock businessClock;
    @Autowired StpProperties properties;
    @MockitoBean StpEventPublisher publisher;

    private UUID companyId;

    @BeforeEach
    void company() throws Exception {
        companyId = newCompany("FINTECH_SA");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        admin.registerKey(companyId, "it", KeyPurpose.SIGNING,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), "it");
    }

    @AfterEach
    void clock() {
        businessClock.resetShift();
    }

    /** STP's company name is unique: a second company needs its own. */
    private UUID newCompany(String stpEmpresa) {
        return companies.save(StpCompany.create("DEMO-" + UUID.randomUUID().toString().substring(0, 8), stpEmpresa,
                90646, "CR", "646", "180", "0000")).getCompanyId();
    }

    private StpPaymentOrder register(UUID company, String amount) {
        businessClock.shiftTo(DAY.atTime(10, 0));
        UUID request = UUID.randomUUID();
        registrar.register(new RegisterPaymentOrderCommand(request, company, new BigDecimal(amount), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                "DISPOSICION", 1L, null, "corr-1",
                UUID.randomUUID(), CLABE_ORDENANTE, "FINTECH SA DE CV", "FSE200101AB1", "CLI-001"));
        return orders.findByPaymentRequestId(request).orElseThrow();
    }

    /** Runs the relay as its job would, letting each backoff pass, until the order no longer waits to be sent. */
    private StpPaymentOrder relayUntilSettledDown(StpPaymentOrder order) {
        LocalDateTime at = DAY.atTime(10, 0);
        for (int i = 0; i < 12; i++) {
            businessClock.shiftTo(at);
            relay.relayPending();
            StpPaymentOrder now = reload(order);
            if (now.status() != StpPaymentOrderStatus.PENDING) {
                return now;
            }
            at = at.plusMinutes(30);   // past any backoff: it tops out at a few minutes
        }
        throw new AssertionError("la orden sigue esperando su envío");
    }

    private StpPaymentOrder reload(StpPaymentOrder order) {
        return orders.findById(order.getStpPaymentOrderId()).orElseThrow();
    }

    private void pollAt(LocalTime time) {
        businessClock.shiftTo(DAY.atTime(time));
        poller.pollInFlightOrders();
    }

    private List<String> trail(StpPaymentOrder order) {
        return jdbcTemplate.queryForList("SELECT to_status FROM stp.payment_order_events "
                + "WHERE stp_payment_order_id = ? ORDER BY occurred_at, event_id", String.class, order.getStpPaymentOrderId());
    }

    @Test
    @DisplayName("sin respuesta de STP no se falla a ciegas: espera a la conciliación y, cerrado el día sin ella, falla y avisa")
    void noAnswerWaitsForTheClosedDay() {
        StpPaymentOrder order = relayUntilSettledDown(register(companyId, "1000.10"));   // .10: the call times out

        assertThat(order.status()).as("sin respuesta no es un no").isEqualTo(StpPaymentOrderStatus.SENT);
        assertThat(order.getErrorDetail()).contains("Sin respuesta de STP tras 6 intentos");
        verify(publisher, never()).publishOrderRejected(any(), any(), any());
        verify(publisher, never()).publishOrderNotRegistered(any(), any());

        // The same day, before its cutoff and inside the hold after it: STP's listing of the day is not final yet.
        pollAt(LocalTime.of(15, 0));
        pollAt(LocalTime.of(18, 5));
        assertThat(reload(order).status()).isEqualTo(StpPaymentOrderStatus.SENT);
        verify(publisher, never()).publishOrderNotRegistered(any(), any());

        // The day closed and its complete listing does not have it: STP never registered it, so paying again is safe.
        pollAt(LocalTime.of(18, 15));
        StpPaymentOrder failed = reload(order);
        assertThat(failed.status()).isEqualTo(StpPaymentOrderStatus.FAILED);
        assertThat(failed.getErrorDetail()).contains("STP no la registró");
        verify(publisher, times(1)).publishOrderNotRegistered(
                argThat(o -> o.getStpPaymentOrderId().equals(order.getStpPaymentOrderId())), contains("no la registró"));
        assertThat(trail(order)).as("la bitácora cuenta cómo llegó ahí").endsWith("SENT", "FAILED");

        // A later poll finds nothing to do: the order is terminal and nobody hears it twice.
        pollAt(LocalTime.of(19, 0));
        verify(publisher, times(1)).publishOrderNotRegistered(any(), any());
    }

    @Test
    @DisplayName("si STP sí la tenía y sólo se perdió la respuesta, la conciliación la liquida: nunca se paga dos veces")
    void lostAnswerSettles() {
        StpPaymentOrder accepted = relayUntilSettledDown(register(companyId, "1000.00"));
        assertThat(accepted.status()).isEqualTo(StpPaymentOrderStatus.ACCEPTED);
        // STP registered it, but the answer never came back: the order is left as an unanswered one.
        jdbcTemplate.update("UPDATE stp.payment_orders SET status = 'SENT' WHERE stp_payment_order_id = ?",
                accepted.getStpPaymentOrderId());

        pollAt(LocalTime.of(18, 15));

        assertThat(reload(accepted).status()).as("la conciliación la trae").isEqualTo(StpPaymentOrderStatus.SETTLED);
        verify(publisher).publishOrderSettled(any(), any(), any(), any(), anyBoolean(), any());
        verify(publisher, never()).publishOrderNotRegistered(any(), any());
    }

    @Test
    @DisplayName("un -30 sostenido agota los intentos y termina en un rechazo que la dispersión oye (D58)")
    void exhaustedTransientRejectionIsHeard() {
        StpPaymentOrder order = relayUntilSettledDown(register(companyId, "1000.03"));   // .03: the link in query mode

        assertThat(order.status()).isEqualTo(StpPaymentOrderStatus.FAILED);
        assertThat(order.getErrorDetail()).contains("Agotados los intentos con rechazo transitorio");
        verify(publisher, times(1)).publishOrderRejected(
                argThat(o -> o.getStpPaymentOrderId().equals(order.getStpPaymentOrderId())),
                eq(BanxicoResponseCode.ENLACE_FINANCIERO_MODO_CONSULTAS), contains("Agotados"));
    }

    @Test
    @DisplayName("D63 · una falla técnica que otro intento supera: la primera orden agota el -30 y la siguiente del mismo pago se liquida")
    void aTechnicalFailureTheNextAttemptOvercomes() {
        StpPaymentOrder first = relayUntilSettledDown(register(companyId, "2500.11"));   // .11: fails once, then not

        assertThat(first.status()).isEqualTo(StpPaymentOrderStatus.FAILED);
        verify(publisher).publishOrderRejected(argThat(o -> o.getStpPaymentOrderId().equals(first.getStpPaymentOrderId())),
                eq(BanxicoResponseCode.ENLACE_FINANCIERO_MODO_CONSULTAS), contains("Agotados"));

        // The caller's next attempt: a new payment request of the same amount to the same account.
        StpPaymentOrder second = relayUntilSettledDown(register(companyId, "2500.11"));
        assertThat(second.getTrackingKey()).isNotEqualTo(first.getTrackingKey());
        assertThat(second.status()).isEqualTo(StpPaymentOrderStatus.ACCEPTED);

        pollAt(LocalTime.of(12, 0));
        assertThat(reload(second).status()).isEqualTo(StpPaymentOrderStatus.SETTLED);
        assertThat(reload(first).status()).as("la primera no se revive").isEqualTo(StpPaymentOrderStatus.FAILED);
    }

    @Test
    @DisplayName("una orden aceptada que nunca liquida no se falla al cerrar el día: STP dijo que la tomó (SO-06)")
    void acceptedNeverSettlingIsLeftAlone() {
        StpPaymentOrder order = relayUntilSettledDown(register(companyId, "1000.06"));   // .06: never in the listing
        assertThat(order.status()).isEqualTo(StpPaymentOrderStatus.ACCEPTED);

        pollAt(LocalTime.of(18, 15));
        businessClock.shiftTo(DAY.plusDays(1).atTime(11, 0));
        poller.pollInFlightOrders();

        assertThat(reload(order).status()).isEqualTo(StpPaymentOrderStatus.ACCEPTED);
        verify(publisher, never()).publishOrderNotRegistered(any(), any());
        verify(publisher, atLeastOnce()).publishOrderAccepted(any());
    }

    @Test
    @DisplayName("una orden que ni salió (empresa sin llave de firma) falla y avisa al agotar los intentos")
    void neverLeftFailsAndTells() {
        StpPaymentOrder order = relayUntilSettledDown(register(newCompany("FINTECH_SIN_LLAVE"), "1000.00"));

        assertThat(order.status()).isEqualTo(StpPaymentOrderStatus.FAILED);
        assertThat(order.getErrorDetail()).contains("Agotados los intentos de envío");
        verify(publisher, times(1)).publishOrderNotRegistered(
                argThat(o -> o.getStpPaymentOrderId().equals(order.getStpPaymentOrderId())), contains("Agotados"));
        assertThat(trail(order)).endsWith("FAILED");
    }

    @Test
    @DisplayName("D63 · sin espera entre reintentos (el stack local), un -30 sostenido se agota en una pasada del relay, en el momento en que se registró")
    void withNoBackoffTheAttemptsHappenWhenRegistered() {
        Duration base = properties.getOutbox().getBaseBackoff();
        properties.getOutbox().setBaseBackoff(Duration.ZERO);
        try {
            StpPaymentOrder order = register(companyId, "1000.03");   // .03: the link in query mode, every try
            relay.relayPending();                                      // the clock does not move

            StpPaymentOrder now = reload(order);
            assertThat(now.status()).isEqualTo(StpPaymentOrderStatus.FAILED);
            assertThat(now.getErrorDetail()).contains("Agotados los intentos con rechazo transitorio");
            // Five retries scheduled, the sixth try gives up: every try at the moment the order was registered.
            assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM stp.outbox_messages "
                            + "WHERE aggregate_id = ? AND message_type = 'REGISTER_ORDER'", Integer.class,
                    order.getStpPaymentOrderId()))
                    .isEqualTo(properties.getOutbox().getMaxAttempts() - 1);
            verify(publisher, times(1)).publishOrderRejected(
                    argThat(o -> o.getStpPaymentOrderId().equals(order.getStpPaymentOrderId())),
                    eq(BanxicoResponseCode.ENLACE_FINANCIERO_MODO_CONSULTAS), contains("Agotados"));
        } finally {
            properties.getOutbox().setBaseBackoff(base);
        }
    }

    @Test
    @DisplayName("rendimiento: sin espera entre reintentos, 40 órdenes con -30 sostenido se agotan en menos de 20 s")
    void manyTransientRejectionsExhaustWithoutWaiting() {
        Duration base = properties.getOutbox().getBaseBackoff();
        properties.getOutbox().setBaseBackoff(Duration.ZERO);
        try {
            int count = 40;
            for (int i = 0; i < count; i++) {
                register(companyId, "1000.03");
            }
            String waiting = "SELECT count(*) FROM stp.payment_orders WHERE company_id = ? AND status = 'PENDING'";
            long started = System.nanoTime();
            int passes = 0;
            while (passes < 20 && jdbcTemplate.queryForObject(waiting, Integer.class, companyId) > 0) {
                relay.relayPending();
                passes++;
            }
            long ms = (System.nanoTime() - started) / 1_000_000;

            Integer failed = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM stp.payment_orders WHERE company_id = ? AND status = 'FAILED'", Integer.class, companyId);
            assertThat(failed).isEqualTo(count);
            verify(publisher, times(count)).publishOrderRejected(any(),
                    eq(BanxicoResponseCode.ENLACE_FINANCIERO_MODO_CONSULTAS), contains("Agotados"));
            assertThat(ms).as("%d órdenes, %d intentos cada una, en %d pasadas y %d ms", count,
                    properties.getOutbox().getMaxAttempts(), passes, ms).isLessThan(20_000);
        } finally {
            properties.getOutbox().setBaseBackoff(base);
        }
    }

    @Test
    @DisplayName("rendimiento: 500 órdenes sin respuesta se resuelven en una sola consulta del día cerrado")
    void manyUnansweredResolveInOnePoll() {
        int count = 500;
        for (int i = 0; i < count; i++) {
            register(companyId, "100.10");
        }
        // The relay gave up on all of them without an answer: they wait for the reconciliation.
        jdbcTemplate.update("UPDATE stp.payment_orders SET status = 'SENT', sent_at = ? WHERE company_id = ?",
                Timestamp.from(DAY.atTime(12, 0).atZone(businessClock.zone()).toInstant()), companyId);

        businessClock.shiftTo(DAY.atTime(18, 15));
        long started = System.nanoTime();
        poller.pollInFlightOrders();
        long ms = (System.nanoTime() - started) / 1_000_000;

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stp.payment_orders WHERE company_id = ? AND status = 'FAILED'", Integer.class, companyId);
        assertThat(failed).isEqualTo(count);
        verify(publisher, times(count)).publishOrderNotRegistered(any(), any());
        assertThat(ms).as("%d órdenes resueltas en %d ms", count, ms).isLessThan(30_000);
    }
}
