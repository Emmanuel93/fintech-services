package com.fintech.disbursement.infrastructure.testsupport;

import com.fintech.disbursement.application.port.in.DispatchDisbursementsUseCase;
import com.fintech.disbursement.application.port.out.PayoutRouteResolverPort;
import com.fintech.disbursement.application.port.out.ProviderDispatchPort;
import com.fintech.disbursement.domain.Provider;
import com.fintech.shared.scheduling.PendingWork;
import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The dispatch queue as due work, against a real Postgres: a requested order is pending until the dispatcher hands it
 * to the provider, and while it does; what waits for a later moment is not; due is business time (D65, D66). Plus how
 * long the count takes on a year of orders.
 */
@SpringBootTest(properties = "fintech.test-support.enabled=true")
@ActiveProfiles("test")
class DispatchQueuePendingWorkIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("disbursement"); }

    @Override protected List<String> preserve() { return List.of("company_mappings"); }

    private static final UUID COMPANY = UUID.fromString("6d1b0000-0000-4000-8000-000000000001");
    private static final PayoutRouteResolverPort.PayoutRoute ROUTE = new PayoutRouteResolverPort.PayoutRoute(
            UUID.randomUUID(), "646180000000000012", "FINTECH SA DE CV", "FSE200101AB1", "CLI-001", Provider.STP);

    @Autowired PendingWork queue;
    @Autowired DispatchDisbursementsUseCase dispatcher;
    @Autowired BusinessClock clock;
    /** Treasury answers with a route, and the provider's topic is not needed: what is tested is the queue. */
    @MockitoBean PayoutRouteResolverPort treasury;
    @MockitoBean ProviderDispatchPort provider;

    @AfterEach
    void realClock() {
        clock.resetShift();
    }

    /** {@code n} orders in {@code status}, due at {@code scheduledFor} (null: as soon as they are requested). */
    private void orders(int n, String status, LocalDateTime scheduledFor) {
        jdbcTemplate.update("""
                INSERT INTO disbursement.disbursement_orders (disbursement_id, company_id, source_system, source_type,
                       source_event_id, beneficiary_name, beneficiary_account, beneficiary_account_type, amount,
                       currency, rail, status, created_at, scheduled_for)
                SELECT gen_random_uuid(), ?, 'credit-portfolio', 'DISPOSITION', 'evt-' || gen_random_uuid(),
                       'JUANA PEREZ GOMEZ', '646180157000000004', '40', 1500, 'MXN', 'SPEI', s.status,
                       ?::timestamptz, ?::timestamptz
                  FROM (SELECT ?::text AS status) s, generate_series(1, ?)""",
                COMPANY, Timestamp.from(clock.now()),
                scheduledFor == null ? null : Timestamp.from(scheduledFor.atZone(clock.zone()).toInstant()),
                status, n);
    }

    @Test
    @DisplayName("una orden pedida es trabajo pendiente hasta que el despachador la entrega al proveedor, y mientras la entrega")
    void pendingUntilHandedOff() {
        when(treasury.resolve(any(), any(), any())).thenReturn(Optional.of(ROUTE));
        clock.shiftTo(LocalDateTime.of(2026, 6, 5, 0, 5));
        orders(2, "REQUESTED", null);

        assertThat(queue.name()).isEqualTo("disbursement-dispatch");
        assertThat(queue.pending()).isEqualTo(2);

        AtomicLong duringHandOff = new AtomicLong(-1);
        doAnswer(i -> {
            duringHandOff.compareAndSet(-1, queue.pending());
            return null;
        }).when(provider).dispatch(any(), any());

        assertThat(dispatcher.dispatchDue()).isEqualTo(2);

        // Mid hand-off both orders are DISPATCHED in the table and not yet in the topic: the running cycle counts.
        assertThat(duringHandOff.get()).isEqualTo(1);
        assertThat(queue.pending()).isZero();
    }

    @Test
    @DisplayName("lo que espera otro momento no es trabajo vencido, y el momento es el del reloj de negocio")
    void dueIsBusinessTime() {
        clock.shiftTo(LocalDateTime.of(2026, 6, 5, 0, 5));
        orders(1, "REQUESTED", LocalDateTime.of(2026, 6, 5, 9, 0));   // a retry's backoff, a closed window

        assertThat(queue.pending()).isZero();

        clock.shiftTo(LocalDateTime.of(2026, 6, 5, 10, 0));
        assertThat(queue.pending()).as("vencida a las 9:00 del día simulado, no del reloj real").isEqualTo(1);
    }

    @Test
    @DisplayName("rendimiento: contar lo vencido entre un año de órdenes terminadas y otras que esperan toma milisegundos")
    void performance() {
        clock.shiftTo(LocalDateTime.of(2026, 6, 5, 10, 0));
        orders(100_000, "SETTLED", null);
        orders(1_000, "REQUESTED", LocalDateTime.of(2026, 6, 6, 10, 0));
        orders(500, "REQUESTED", null);
        jdbcTemplate.execute("ANALYZE disbursement.disbursement_orders");
        queue.pending();   // warm-up: the first count pays for the plan

        long started = System.nanoTime();
        long pending = queue.pending();
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(pending).isEqualTo(500);
        assertThat(ms).as("101,500 órdenes, lo vencido contado en %d ms", ms).isLessThan(300);
    }
}
