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
import org.springframework.kafka.test.context.EmbeddedKafka;
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
 *
 * <h2>Por qué hace falta {@code @EmbeddedKafka} aquí, si esto no prueba ningún evento</h2>
 *
 * No lo tenía, y por eso fallaba <b>de forma intermitente</b> en dos de cada tres corridas
 * completas — con tiempos absurdos (120 s, 300 s, 480 s) y un {@code expected: 1 but was: 0}.
 *
 * <p>La cadena era ésta. Sin {@code @EmbeddedKafka}, {@code spring.kafka.bootstrap-servers} se queda
 * en {@code localhost:9092}, que en esta máquina es <b>el broker real de docker-compose</b> cuando
 * el stack está levantado — el estado normal mientras alguien trabaja. Ese broker se anuncia a sí
 * mismo como {@code kafka:9092}, un nombre que sólo resuelve dentro de la red de Docker: el
 * productor conecta, pide metadatos, no puede resolver el host que le contestan y <b>se bloquea
 * {@code max.block.ms} — 60 s por envío</b>. De ahí los múltiplos de 60.
 *
 * <p>Y al agotarse, {@code send(...)} lanza <b>de forma síncrona</b>, dentro de la transacción del
 * devengo: el cargo se revierte y la prueba cuenta cero. Pasaba sólo cuando <b>nada</b> escuchaba en
 * el 9092. Es decir: el resultado dependía de si el desarrollador tenía su stack arriba.
 *
 * <p>El broker embebido corta la dependencia del entorno. <b>La asimetría del publicador —las
 * fallas asíncronas se registran y se tragan, las síncronas tumban la transacción— es un hallazgo
 * aparte y no se arregla desde una prueba</b>: está documentado en el README del servicio.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
// Sin esto la prueba dependía de lo que estuviera corriendo en la máquina, y eso la hacía fallar
// de forma intermitente por un motivo que no tiene nada que ver con lo que mide. Ver el javadoc.
@EmbeddedKafka(partitions = 1, topics = {"charges.charge-applied", "charges.charge-reversed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class AccrualIdempotencyIT {

    @Autowired AccrualScheduleRepository scheduleRepository;
    @Autowired InterestAccrualService accrualService;
    @Autowired DailyAccrualJob dailyAccrualJob;
    @Autowired MoratoriumAccrualJob moratoriumAccrualJob;
    @Autowired JdbcTemplate jdbc;

    private UUID newAccount(boolean conMora) {
        UUID creditAccountId = UUID.randomUUID();
        AccrualSchedule s = AccrualSchedule.create(
                creditAccountId, UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.32000000"), new BigDecimal("0.48000000"),
                new BigDecimal("20000.00"), new BigDecimal("20000.00"), 3);
        // BK-19 · la mora se enciende con lo que cartera midió, no a mano: sin capital vencido la
        // base es cero y no habría cargo que contar. Una cuota de $2 000 vencida hace 10 días.
        if (conMora) {
            s.updateMoratorium(new BigDecimal("2000.00"), LocalDate.now().minusDays(10),
                    LocalDate.now(), 3);
        }
        scheduleRepository.save(s);
        return creditAccountId;
    }

    private int charges(UUID account, String tipo, LocalDate dia) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM charges.charge_records
                 WHERE credit_account_id = ? AND charge_type = ? AND accrual_date = ?
                """, Integer.class, account, tipo, dia);
    }

    @Test
    @DisplayName("el devengo ordinario corrido dos veces el mismo día deja UN cargo")
    void ordinarioNoSeDuplica() {
        UUID account = newAccount(false);
        LocalDate hoy = LocalDate.now();

        dailyAccrualJob.runDailyAccrualFor(hoy);
        dailyAccrualJob.runDailyAccrualFor(hoy);

        assertThat(charges(account, "ORDINARY_INTEREST", hoy)).isEqualTo(1);
    }

    @Test
    @DisplayName("el devengo MORATORIO corrido dos veces el mismo día deja UN cargo")
    void moratorioNoSeDuplica() {
        // Antes de TK-02 esto daba 2: `accrueMoratoriumForSchedule` no marcaba la fecha y no
        // había restricción que lo impidiera. Se duplicaba con una sola réplica.
        UUID account = newAccount(true);
        LocalDate hoy = LocalDate.now();

        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);
        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);

        assertThat(charges(account, "MORATORIUM_INTEREST", hoy)).isEqualTo(1);
    }

    @Test
    @DisplayName("ordinario y moratorio del mismo día conviven: son DOS IVA, no un choque")
    void dosIvaElMismoDiaConviven() {
        // El detalle que habría roto una restricción total sobre (cuenta, tipo, fecha): un mismo
        // día genera IVA del interés ordinario Y del moratorio. La restricción es parcial por eso.
        UUID account = newAccount(true);
        LocalDate hoy = LocalDate.now();

        dailyAccrualJob.runDailyAccrualFor(hoy);
        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);

        assertThat(charges(account, "ORDINARY_INTEREST", hoy)).isEqualTo(1);
        assertThat(charges(account, "MORATORIUM_INTEREST", hoy)).isEqualTo(1);
        assertThat(charges(account, "IVA", hoy)).isEqualTo(2);
    }

    @Test
    @DisplayName("el reloj del moratorio no le roba el día al ordinario")
    void relojesSeparados() {
        // Si compartieran `lastAccrualDate`, correr el moratorio primero dejaría al interés
        // ordinario de ese día sin devengar: un cargo omitido en vez de uno duplicado.
        UUID account = newAccount(true);
        LocalDate hoy = LocalDate.now();

        moratoriumAccrualJob.runMoratoriumAccrualFor(hoy);
        dailyAccrualJob.runDailyAccrualFor(hoy);

        assertThat(charges(account, "MORATORIUM_INTEREST", hoy)).isEqualTo(1);
        assertThat(charges(account, "ORDINARY_INTEREST", hoy))
                .as("el ordinario se devenga aunque el moratorio haya corrido primero")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("días distintos devengan cargos distintos: la guarda no bloquea el avance")
    void diasDistintosSiDevengan() {
        UUID account = newAccount(false);
        LocalDate d1 = LocalDate.now().minusDays(2);
        LocalDate d2 = LocalDate.now().minusDays(1);

        accrualService.rewindAllTo(d1.minusDays(1));
        dailyAccrualJob.runDailyAccrualFor(d1);
        dailyAccrualJob.runDailyAccrualFor(d2);

        assertThat(charges(account, "ORDINARY_INTEREST", d1)).isEqualTo(1);
        assertThat(charges(account, "ORDINARY_INTEREST", d2)).isEqualTo(1);
    }

    @Test
    @DisplayName("SIN candado, dos hilos concurrentes sobre la misma cuenta dejan UN cargo")
    void concurrenciaSinCandado() throws Exception {
        // La prueba que justifica el orden del plan: si el candado distribuido fallara o se
        // apagara, la clave natural sigue siendo la que impide el dato duplicado. El candado
        // ahorra trabajo; esto garantiza corrección.
        UUID account = newAccount(false);
        LocalDate hoy = LocalDate.now();
        UUID scheduleId = scheduleRepository.findByCreditAccountId(account).orElseThrow().getScheduleId();

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

        assertThat(charges(account, "ORDINARY_INTEREST", hoy))
                .as("ocho hilos a la vez, un solo cargo (%d ok, %d rebotados)", exitos.get(), rebotes.get())
                .isEqualTo(1);
        assertThat(charges(account, "IVA", hoy)).isEqualTo(1);
    }
}
