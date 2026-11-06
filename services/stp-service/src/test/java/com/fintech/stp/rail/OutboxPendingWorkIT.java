package com.fintech.stp.rail;

import com.fintech.shared.scheduling.PendingWork;
import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.port.in.ManageCompanyUseCase;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.in.RelayOutboxUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpEventPublisher;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox as due work, against a real Postgres and the real relay: an order registered is pending until the relay
 * sends it; one held around the cutoff is not, until its hold passes; due is business time. Plus how long the count
 * takes on a year of messages.
 */
@SpringBootTest(properties = "fintech.test-support.enabled=true")
@ActiveProfiles("test")
class OutboxPendingWorkIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("stp"); }

    /** The bank calendar is a catalog seeded by Liquibase (015); truncating it would date orders on holidays. */
    @Override protected List<String> preserve() { return List.of("business_calendar_days"); }

    /** A Tuesday with no holiday near: the day closes at 18:00, and the hold around it runs 17:50 to 18:10. */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    @Autowired PendingWork outbox;
    @Autowired RegisterPaymentOrderUseCase registrar;
    @Autowired RelayOutboxUseCase relay;
    @Autowired StpCompanyRepository companies;
    @Autowired ManageCompanyUseCase admin;
    @Autowired BusinessClock businessClock;
    @MockitoBean StpEventPublisher publisher;

    private UUID companyId;

    @BeforeEach
    void company() throws Exception {
        companyId = companies.save(StpCompany.create("DEMO-" + UUID.randomUUID().toString().substring(0, 8),
                "FINTECH_SA", 90646, "CR", "646", "180", "0000")).getCompanyId();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        admin.registerKey(companyId, "it", KeyPurpose.SIGNING,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), "it");
    }

    @AfterEach
    void realClock() {
        businessClock.resetShift();
    }

    private void register(String amount) {
        registrar.register(new RegisterPaymentOrderCommand(UUID.randomUUID(), companyId, new BigDecimal(amount), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                "DISPOSICION", 1L, null, "corr-1",
                UUID.randomUUID(), "646180000000000012", "FINTECH SA DE CV", "FSE200101AB1", "CLI-001"));
    }

    @Test
    @DisplayName("una orden registrada es trabajo pendiente hasta que el relay la envía a STP")
    void pendingUntilRelayed() {
        businessClock.shiftTo(DAY.atTime(10, 0));
        register("1500.00");
        register("820.00");

        assertThat(outbox.name()).isEqualTo("stp-outbox");
        assertThat(outbox.pending()).isEqualTo(2);

        relay.relayPending();

        assertThat(outbox.pending()).isZero();
    }

    @Test
    @DisplayName("la orden retenida por el corte no es trabajo vencido hasta que su espera pasa en el reloj de negocio")
    void heldOrderIsNotDueUntilTheHoldPasses() {
        businessClock.shiftTo(DAY.atTime(17, 55));
        register("1500.00");

        assertThat(outbox.pending()).as("retenida hasta las 18:10").isZero();

        businessClock.shiftTo(DAY.atTime(18, 11));
        assertThat(outbox.pending()).isEqualTo(1);
        relay.relayPending();
        assertThat(outbox.pending()).isZero();
    }

    @Test
    @DisplayName("rendimiento: contar lo vencido entre un año de mensajes enviados y otros que esperan toma milisegundos")
    void performance() {
        businessClock.shiftTo(DAY.atTime(10, 0));
        Timestamp now = Timestamp.from(businessClock.now());
        // Sent a day ago, waiting an hour more, and due five seconds ago.
        String insert = """
                INSERT INTO stp.outbox_messages (outbox_id, message_type, aggregate_id, payload, status, next_attempt_at,
                       created_at, sent_at)
                SELECT gen_random_uuid(), 'REGISTER_ORDER', gen_random_uuid(), '{}', s.status,
                       ?::timestamptz + make_interval(secs => ?), ?::timestamptz,
                       CASE WHEN s.status = 'SENT' THEN ?::timestamptz END
                  FROM (SELECT ?::text AS status) s, generate_series(1, ?)""";
        jdbcTemplate.update(insert, now, -86_400, now, now, "SENT", 200_000);
        jdbcTemplate.update(insert, now, 3_600, now, now, "PENDING", 1_000);
        jdbcTemplate.update(insert, now, -5, now, now, "PENDING", 500);
        jdbcTemplate.execute("ANALYZE stp.outbox_messages");
        outbox.pending();   // warm-up: the first count pays for the plan

        long started = System.nanoTime();
        long pending = outbox.pending();
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(pending).isEqualTo(500);
        assertThat(ms).as("201,500 mensajes, lo vencido contado en %d ms", ms).isLessThan(300);
    }
}
