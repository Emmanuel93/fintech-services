package com.fintech.charges;

import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.service.InterestAccrualService;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.infrastructure.job.DailyAccrualJob;
import com.fintech.charges.infrastructure.job.MoratoriumAccrualJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TK-02 — la clave natural impide el cargo duplicado, <b>venga de donde venga</b>.
 *
 * <p>Es el cimiento del candado distribuido y no su sustituto: el candado evita el trabajo
 * duplicado, esto evita el dato duplicado. La prueba que importa es la última — con el candado
 * fuera de juego, dos hilos devengando la misma cuenta a la vez sólo consiguen un cargo.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DevengoIdempotenciaIT {

    @Autowired AccrualScheduleRepository scheduleRepository;
    @Autowired InterestAccrualService accrualService;
    @Autowired DailyAccrualJob dailyAccrualJob;
    @Autowired MoratoriumAccrualJob moratoriumAccrualJob;
    @Autowired JdbcTemplate jdbc;

    private UUID nuevaCuenta(boolean conMora) {
        UUID creditAccountId = UUID.randomUUID();
        AccrualSchedule s = AccrualSchedule.create(
                creditAccountId, UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.32000000"), new BigDecimal("0.48000000"),
                new BigDecimal("20000.00"), new BigDecimal("20000.00"), 3);
        // BK-19 · la mora se enciende con lo que cartera midió, no a mano: sin capital vencido la
        // base es cero y no habría cargo que contar. Una cuota de $2 000 vencida hace 10 días.
        if (conMora) {
            s.actualizarMora(new BigDecimal("2000.00"), LocalDate.now().minusDays(10),
                    LocalDate.now(), 3);
        }
        scheduleRepository.save(s);
        return creditAccountId;
    }

    private int cargos(UUID cuenta, String tipo, LocalDate dia) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM charges.charge_records
                 WHERE credit_account_id = ? AND charge_type = ? AND accrual_date = ?
                """, Integer.class, cuenta, tipo, dia);
    }

    @Test
    @DisplayName("el devengo ordinario corrido dos veces el mismo día deja UN cargo")
    void ordinarioNoSeDuplica() {
        UUID cuenta = nuevaCuenta(false);
        LocalDate hoy = LocalDate.now();

        dailyAccrualJob.runDailyAccrualFor(hoy);
        dailyAccrualJob.runDailyAccrualFor(hoy);

        assertThat(cargos(cuenta, "ORDINARY_INTEREST", hoy)).isEqualTo(1);
    }

    @Test
    @DisplayName("el devengo MORATORIO corrido dos veces el mismo día deja UN cargo")
    void moratorioNoSeDuplica() {
        // Antes de TK-02 esto daba 2: `accrueMoratoriumForSchedule` no marcaba la fecha y no
        // había restricción que lo impidiera. Se duplicaba con una sola réplica.
        UUID cuenta = nuevaCuenta(true);
        LocalDate hoy = LocalDate.now();

        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);
        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);

        assertThat(cargos(cuenta, "MORATORIUM_INTEREST", hoy)).isEqualTo(1);
    }

    @Test
    @DisplayName("ordinario y moratorio del mismo día conviven: son DOS IVA, no un choque")
    void dosIvaElMismoDiaConviven() {
        // El detalle que habría roto una restricción total sobre (cuenta, tipo, fecha): un mismo
        // día genera IVA del interés ordinario Y del moratorio. La restricción es parcial por eso.
        UUID cuenta = nuevaCuenta(true);
        LocalDate hoy = LocalDate.now();

        dailyAccrualJob.runDailyAccrualFor(hoy);
        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);

        assertThat(cargos(cuenta, "ORDINARY_INTEREST", hoy)).isEqualTo(1);
        assertThat(cargos(cuenta, "MORATORIUM_INTEREST", hoy)).isEqualTo(1);
        assertThat(cargos(cuenta, "IVA", hoy)).isEqualTo(2);
    }

    @Test
    @DisplayName("el reloj del moratorio no le roba el día al ordinario")
    void relojesSeparados() {
        // Si compartieran `lastAccrualDate`, correr el moratorio primero dejaría al interés
        // ordinario de ese día sin devengar: un cargo omitido en vez de uno duplicado.
        UUID cuenta = nuevaCuenta(true);
        LocalDate hoy = LocalDate.now();

        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);
        dailyAccrualJob.runDailyAccrualFor(hoy);

        assertThat(cargos(cuenta, "MORATORIUM_INTEREST", hoy)).isEqualTo(1);
        assertThat(cargos(cuenta, "ORDINARY_INTEREST", hoy))
                .as("el ordinario se devenga aunque el moratorio haya corrido primero")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("días distintos devengan cargos distintos: la guarda no bloquea el avance")
    void diasDistintosSiDevengan() {
        UUID cuenta = nuevaCuenta(false);
        LocalDate d1 = LocalDate.now().minusDays(2);
        LocalDate d2 = LocalDate.now().minusDays(1);

        accrualService.rewindAllTo(d1.minusDays(1));
        dailyAccrualJob.runDailyAccrualFor(d1);
        dailyAccrualJob.runDailyAccrualFor(d2);

        assertThat(cargos(cuenta, "ORDINARY_INTEREST", d1)).isEqualTo(1);
        assertThat(cargos(cuenta, "ORDINARY_INTEREST", d2)).isEqualTo(1);
    }

    @Test
    @DisplayName("SIN candado, dos hilos concurrentes sobre la misma cuenta dejan UN cargo")
    void concurrenciaSinCandado() throws Exception {
        // La prueba que justifica el orden del plan: si el candado distribuido fallara o se
        // apagara, la clave natural sigue siendo la que impide el dato duplicado. El candado
        // ahorra trabajo; esto garantiza corrección.
        UUID cuenta = nuevaCuenta(false);
        LocalDate hoy = LocalDate.now();
        UUID scheduleId = scheduleRepository.findByCreditAccountId(cuenta).orElseThrow().getScheduleId();

        int hilos = 8;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch fin = new CountDownLatch(hilos);
        AtomicInteger exitos = new AtomicInteger();
        AtomicInteger rebotes = new AtomicInteger();

        for (int i = 0; i < hilos; i++) {
            pool.submit(() -> {
                try {
                    salida.await();
                    accrualService.accrueInterestForSchedule(scheduleId, hoy);
                    exitos.incrementAndGet();
                } catch (Exception e) {
                    rebotes.incrementAndGet();   // la restricción hizo su trabajo
                } finally {
                    fin.countDown();
                }
            });
        }
        salida.countDown();
        assertThat(fin.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(cargos(cuenta, "ORDINARY_INTEREST", hoy))
                .as("ocho hilos a la vez, un solo cargo (%d ok, %d rebotados)", exitos.get(), rebotes.get())
                .isEqualTo(1);
        assertThat(cargos(cuenta, "IVA", hoy)).isEqualTo(1);
    }
}
