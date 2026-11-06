package com.fintech.charges;

import com.fintech.shared.time.BusinessClock;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.infrastructure.job.DayCloseAccrualJob;
import com.fintech.charges.support.PortfolioSnapshotTestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Dos réplicas disparan el mismo cron al mismo tiempo: sólo una lo corre.
 *
 * <p>Antes cada pod tenía su {@code @Scheduled} y nada lo coordinaba; aquí la clave natural del
 * devengo frenaba el cargo duplicado, pero el trabajo se hacía dos veces —y en billing y risk ni
 * eso—. El candado vive en {@code charges.shedlock} y lo decide el reloj de PostgreSQL.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Import(PortfolioSnapshotTestConfig.class)
// Mismo motivo que en AccrualIdempotencyIT: sin broker embebido la prueba depende del stack local.
@EmbeddedKafka(partitions = 1, topics = {"charges.charge-applied", "charges.charge-reversed", "charges.day-accrued",
        "credit-portfolio.day-closed", "credit-portfolio.balance-updated", "credit-portfolio.delinquency-status-updated",
        "closing.cutoff-run-completed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ScheduledJobLockIT {

    @Autowired DayCloseAccrualJob dayCloseAccrualJob;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AccrualUnitStore units;

    @BeforeEach
    void sinCandados() {
        jdbc.update("DELETE FROM charges.shedlock");
        // Sin bitácora: el cron toma ayer como su primer día y lo devenga.
        jdbc.update("DELETE FROM charges.accrual_units");
        jdbc.update("DELETE FROM charges.accrual_days");
        jdbc.update("DELETE FROM charges.portfolio_day_closes");
        jdbc.update("DELETE FROM charges.cutoff_completions");
        // A platform with no account born before yesterday closes nothing; one closed account is enough to
        // make yesterday a day to close.
        jdbc.update("DELETE FROM charges.accrual_schedules WHERE schedule_id = ?", CUENTA_DE_AYER);
        // Born ten business days ago: the job measures "yesterday" with the business clock, which another test may
        // have left moved; the database's now() would make the account look born in the future.
        Timestamp hace10Dias = Timestamp.from(BusinessClock.current().now().minus(10, ChronoUnit.DAYS));
        jdbc.update("""
                INSERT INTO charges.accrual_schedules (schedule_id, credit_account_id, obligor_party_id, product_type,
                       product_behavior, status, nominal_rate, moratorium_rate, principal_balance, approved_amount,
                       created_at, updated_at)
                VALUES (?, ?, ?, 'PERSONAL_LOAN', 'INSTALLMENT', 'CLOSED', 0.36, 0.72, 0, 1000, ?, ?)""",
                CUENTA_DE_AYER, CUENTA_DE_AYER, CUENTA_DE_AYER, hace10Dias, hace10Dias);
    }

    @AfterEach
    void sinLaCuentaDeAyer() {
        jdbc.update("DELETE FROM charges.accrual_schedules WHERE schedule_id = ?", CUENTA_DE_AYER);
    }

    private static final UUID CUENTA_DE_AYER = UUID.fromString("00000000-0000-0000-0000-00000000a10c");

    @Test
    @DisplayName("la tabla del candado vive en el esquema del servicio")
    void laTablaViveEnElEsquemaDelServicio() {
        Integer tablas = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'charges' AND table_name = 'shedlock'""", Integer.class);
        assertThat(tablas).isEqualTo(1);
    }

    @Test
    @DisplayName("dos réplicas a la vez: una cierra el día, la otra lo salta sin esperar")
    void dosReplicasUnaSolaCorrida() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Long>> corridas = new ArrayList<>();
        for (int replica = 0; replica < 2; replica++) {
            corridas.add(pool.submit(() -> {
                salida.await();
                long inicio = System.nanoTime();
                dayCloseAccrualJob.run();
                return (System.nanoTime() - inicio) / 1_000_000;
            }));
        }
        salida.countDown();
        List<Long> duraciones = new ArrayList<>();
        for (Future<Long> f : corridas) duraciones.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();

        // Una sola réplica repartió el día: el reparto pasa una vez por la bitácora.
        verify(units, times(1)).plan(any(), any());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM charges.shedlock WHERE name = 'charges.day-close'", Integer.class))
                .isEqualTo(1);
        // Rendimiento: la réplica que no toma el candado regresa de inmediato; no se forma en fila.
        assertThat(duraciones.stream().mapToLong(Long::longValue).min().orElseThrow()).isLessThan(2_000L);
    }
}
