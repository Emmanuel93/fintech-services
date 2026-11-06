package com.fintech.creditportfolio.dayclose;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.Claim;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.infrastructure.job.DayCloseRunner;
import com.fintech.creditportfolio.infrastructure.job.DaySnapshotRunner;
import com.fintech.creditportfolio.infrastructure.job.DelinquencyCalculationJob;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La parte de cartera en el cierre del día, contra Postgres y Kafka reales: el aviso de cargos
 * dispara la mora al día siguiente y el aviso a closing; repetido, no recalcula.
 */
@SpringBootTest(properties = {
        // La espera en línea de la compuerta, corta: la prueba del aviso adelantado no aguarda 10 minutos.
        "fintech.day-close.gate-wait=PT3S",
        "fintech.day-close.gate-poll=PT0.2S",
        // La reanudación la llama la prueba cuando la necesita.
        "fintech.day-close.retry-cron=-",
        // Test dates are in the future: the snapshot gate "day has ended" is unit-tested elsewhere.
        "fintech.day-close.allow-future-dates=true"
})
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "charges.day-accrued", "charges.charge-applied", "charges.charge-reversed", "credit-portfolio.day-closed",
        "credit-portfolio.balance-updated", "credit-portfolio.delinquency-status-updated",
        "credit-portfolio.installment-due", "credit-portfolio.installment-upcoming",
        "charges.account-accrued", "credit-portfolio.account-day-closed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class DayCloseIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    private static Consumer<String, String> avisos;

    @Autowired CreditAccountRepository accountRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired JdbcTemplate jdbc;
    @Autowired DayCloseRepository dayCloses;
    @Autowired DayCloseRunner runner;
    @Autowired DaySnapshotRunner snapshots;
    @Autowired com.fintech.creditportfolio.infrastructure.job.AccountDayReleaser releaser;
    @Autowired EmbeddedKafkaBroker broker;
    @MockitoSpyBean DelinquencyCalculationJob delinquency;

    @BeforeAll
    static void escucharAvisos(@Autowired EmbeddedKafkaBroker broker) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("closing-de-prueba", "true", broker);
        avisos = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        broker.consumeFromAnEmbeddedTopic(avisos, "credit-portfolio.day-closed");
    }

    @AfterAll
    static void cerrar() {
        if (avisos != null) avisos.close();
    }

    @org.junit.jupiter.api.BeforeEach
    void bitacoraLimpia() {
        jdbc.update("DELETE FROM credit_portfolio.day_closes");
        jdbc.update("DELETE FROM credit_portfolio.account_day_snapshots");
        jdbc.update("DELETE FROM credit_portfolio.day_snapshots");
        jdbc.update("DELETE FROM credit_portfolio.charges_day_accruals");
        jdbc.update("DELETE FROM credit_portfolio.account_day_releases");
        org.mockito.Mockito.clearInvocations(delinquency);
    }

    @Test
    @DisplayName("el aviso de cargos del 10, con sus cifras, cierra el 10: mora al 11 y aviso a closing; repetido no recalcula")
    void elAvisoDeCargosCierraElDia() {
        LocalDate diez = LocalDate.of(2026, 11, 10);
        UUID cuenta = cuentaActiva("20000");
        interes(cuenta, diez, "13.33");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(diez) == 1);

        foto(diez);
        kafkaTemplate.send("charges.day-accrued", diez.toString(), avisoDeCargos(diez, 1, "13.33"));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(dayCloses.find(diez)).hasValueSatisfying(d -> {
                    assertThat(d.status()).isEqualTo("DONE");
                    assertThat(d.delinquencyAsOf()).isEqualTo(diez.plusDays(1));
                    assertThat(d.triggeredBy()).isEqualTo("charges.day-accrued");
                }));
        assertThat(avisoACierreDe(diez)).isTrue();
        verify(delinquency, times(1)).runFor(diez.plusDays(1));

        // El mismo aviso otra vez: vuelve a avisar a closing, pero la mora no se recalcula.
        foto(diez);
        kafkaTemplate.send("charges.day-accrued", diez.toString(), avisoDeCargos(diez, 1, "13.33"));
        assertThat(avisoACierreDe(diez)).isTrue();
        verify(delinquency, times(1)).runFor(diez.plusDays(1));
    }

    @Test
    @DisplayName("el aviso llega antes que el último interés: el día espera, y cierra cuando el interés llega")
    void elAvisoAdelantadoEspera() {
        LocalDate d = LocalDate.of(2026, 11, 12);
        UUID cuenta = cuentaActiva("20000");

        // Cargos avisa dos intereses; a cartera sólo le ha llegado uno.
        interes(cuenta, d, "10.00");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(d) == 1);
        foto(d);
        kafkaTemplate.send("charges.day-accrued", d.toString(), avisoDeCargos(d, 2, "20.00"));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(dayCloses.find(d)).hasValueSatisfying(c -> {
                    assertThat(c.status()).isEqualTo("WAITING");
                    assertThat(c.waitingFor()).contains("no cuadran").contains("cartera tiene 1 por 10.00");
                }));
        verify(delinquency, never()).runFor(d.plusDays(1));

        // Llega el que faltaba (o cargos lo reenvía): la reanudación cierra el día.
        interes(cuenta, d, "10.00");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(d) == 2);
        assertThat(runner.advance("reanudación")).extracting(DayCloseRunner.Result::outcome)
                .containsExactly(DayCloseRunner.Outcome.CLOSED);
        verify(delinquency, times(1)).runFor(d.plusDays(1));
    }

    @Test
    @DisplayName("un interés rechazado —cuenta castigada— también llegó: cuenta para cuadrar y queda escrito")
    void elRechazadoTambienCuenta() {
        LocalDate d = LocalDate.of(2026, 11, 14);
        UUID castigada = cuentaActiva("5000");
        CreditAccount c = accountRepository.findById(castigada).orElseThrow();
        c.executeWriteOff();
        accountRepository.save(c);

        String id = interes(castigada, d, "4.10");
        await().atMost(15, TimeUnit.SECONDS).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM credit_portfolio.charge_rejections WHERE source_event_id = ?", Integer.class, id) == 1);

        jdbc.update("""
                INSERT INTO credit_portfolio.charges_day_accruals
                       (business_date, ordinary_charges, ordinary_amount, moratorium_charges, moratorium_amount)
                VALUES (?, 1, 4.10, 0, 0)""", d);
        foto(d);
        assertThat(runner.close(d, "prueba").outcome()).isEqualTo(DayCloseRunner.Outcome.CLOSED);

        // El reenvío del mismo cargo no se vuelve a rechazar.
        interes(castigada, d, "4.10", id);
        await().pollDelay(1, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).until(() -> true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.charge_rejections WHERE value_date = ?",
                Integer.class, d)).isEqualTo(1);
    }

    @Test
    @DisplayName("un interés del mismo día que no salió del cierre (siembra, historia) no entra a las cifras")
    void soloCuentaElLote() {
        LocalDate d = LocalDate.of(2026, 11, 18);
        UUID cuenta = cuentaActiva("8000");
        Map<String, Object> sembrado = new HashMap<>();
        sembrado.put("eventId", "it-sembrado-" + UUID.randomUUID());
        sembrado.put("creditAccountId", cuenta.toString());
        sembrado.put("chargeType", "ORDINARY_INTEREST");
        sembrado.put("totalAmount", new BigDecimal("99.00"));
        sembrado.put("effectiveDate", d.toString());
        kafkaTemplate.send("charges.charge-applied", cuenta.toString(), sembrado);
        interes(cuenta, d, "5.00");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(d) == 2);

        jdbc.update("""
                INSERT INTO credit_portfolio.charges_day_accruals
                       (business_date, ordinary_charges, ordinary_amount, moratorium_charges, moratorium_amount)
                VALUES (?, 1, 5.00, 0, 0)""", d);

        foto(d);
        assertThat(runner.close(d, "prueba").outcome()).isEqualTo(DayCloseRunner.Outcome.CLOSED);
    }

    @Test
    @DisplayName("las cuentas que cargos dejó fuera del lote no cuentan en las cifras y viajan en el aviso a closing")
    void heldAccountsStayOutOfTheTotals() {
        LocalDate dia = LocalDate.of(2026, 12, 14);
        UUID sana = cuentaActiva("20000");
        UUID retenida = cuentaActiva("29");
        interes(sana, dia, "10.00");
        // La retenida devengó tarde mientras cartera aún no cerraba: su cargo lleva el mismo lote.
        interes(retenida, dia, "0.02");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(dia) == 2);

        foto(dia);
        kafkaTemplate.send("charges.day-accrued", dia.toString(), avisoDeCargos(dia, 1, "10.00", List.of(retenida)));

        await().atMost(30, TimeUnit.SECONDS).until(() ->
                dayCloses.find(dia).filter(DayCloseRepository.DayClose::done).isPresent());
        assertThat(dayCloses.chargesDay(dia)).hasValueSatisfying(c ->
                assertThat(c.heldAccounts()).containsExactly(retenida));
        String aviso = avisoACierre(dia);
        assertThat(aviso).contains("heldAccounts").contains(retenida.toString());
    }

    @Test
    @DisplayName("una cuenta que devengó tarde se suelta a closing cuando sus intereses de ese día cuadran, no antes")
    void releasesALateAccountWhenItsInterestMatches() {
        LocalDate dia = LocalDate.of(2026, 12, 15);
        UUID sana = cuentaActiva("20000");
        UUID retenida = cuentaActiva("29");
        interes(sana, dia, "10.00");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(dia) == 1);
        foto(dia);
        kafkaTemplate.send("charges.day-accrued", dia.toString(), avisoDeCargos(dia, 1, "10.00", List.of(retenida)));
        await().atMost(30, TimeUnit.SECONDS).until(() ->
                dayCloses.find(dia).filter(DayCloseRepository.DayClose::done).isPresent());

        // El aviso tardío llega antes que su cargo: la cuenta espera, con el motivo escrito.
        kafkaTemplate.send("charges.account-accrued", retenida.toString(), Map.of(
                "eventId", UUID.randomUUID().toString(), "businessDate", dia.toString(),
                "creditAccountId", retenida.toString(), "ordinaryCharges", 1, "ordinaryAmount", new BigDecimal("0.02"),
                "moratoriumCharges", 0, "moratoriumAmount", BigDecimal.ZERO));
        await().atMost(30, TimeUnit.SECONDS).until(() -> releaseOf(dia, retenida) != null
                && releaseOf(dia, retenida).get("waiting_for") != null);
        assertThat(releaseOf(dia, retenida)).containsEntry("status", "PENDING");
        assertThat((String) releaseOf(dia, retenida).get("waiting_for")).contains("no cuadran");

        interes(retenida, dia, "0.02");
        await().atMost(15, TimeUnit.SECONDS).until(() -> interesesDe(dia) == 2);
        assertThat(releaser.releasePending()).isEqualTo(1);

        assertThat(releaseOf(dia, retenida)).containsEntry("status", "RELEASED");
        assertThat(released()).contains(retenida.toString());
    }

    @Test
    @DisplayName("sin el aviso de cargos, ni el soporte de pruebas cierra el día")
    void sinAvisoNoCierra() {
        LocalDate d = LocalDate.of(2026, 11, 16);

        foto(d);
        var r = runner.close(d, "test-support");

        assertThat(r.outcome()).isEqualTo(DayCloseRunner.Outcome.WAITING);
        assertThat(r.detail()).contains("cargos no ha avisado");
        verify(delinquency, never()).runFor(d.plusDays(1));
    }

    @Test
    @DisplayName("la bitácora: un día se toma una vez, se retoma si falló o si quien lo tenía murió")
    void laBitacoraTomaElDiaUnaVez() {
        LocalDate d = LocalDate.of(2026, 11, 20);
        Duration dosHoras = Duration.ofHours(2);

        assertThat(dayCloses.claim(d, d.plusDays(1), "a", dosHoras)).isEqualTo(Claim.CLAIMED);
        assertThat(dayCloses.claim(d, d.plusDays(1), "b", dosHoras)).isEqualTo(Claim.IN_PROGRESS);

        dayCloses.fail(d, "se cayó la base");
        assertThat(dayCloses.claim(d, d.plusDays(1), "respaldo", dosHoras)).isEqualTo(Claim.CLAIMED);

        jdbc.update("UPDATE credit_portfolio.day_closes SET started_at = now() - interval '3 hours' WHERE business_date = ?", d);
        assertThat(dayCloses.claim(d, d.plusDays(1), "otro pod", dosHoras)).as("el que lo tenía murió").isEqualTo(Claim.CLAIMED);

        dayCloses.complete(d, 10, 0);
        assertThat(dayCloses.claim(d, d.plusDays(1), "tarde", dosHoras)).isEqualTo(Claim.ALREADY_DONE);
        dayCloses.waiting(d, d.plusDays(1), "tarde", "no pisa un día cerrado");
        assertThat(dayCloses.find(d).orElseThrow().status()).isEqualTo("DONE");

        LocalDate e = d.plusDays(1);
        dayCloses.waiting(e, e.plusDays(1), "aviso", "faltan cargos");
        assertThat(dayCloses.find(e).orElseThrow().waitingFor()).isEqualTo("faltan cargos");
        assertThat(dayCloses.claim(e, e.plusDays(1), "reanudación", dosHoras)).as("un día en espera se retoma")
                .isEqualTo(Claim.CLAIMED);
    }

    @Test
    @DisplayName("el devengo del 10 aplicado el 11 queda en el libro con fecha valor del 10")
    void elDevengoLlevaSuFechaValor() {
        UUID cuenta = cuentaActiva("10000");
        Map<String, Object> cargo = new HashMap<>();
        cargo.put("eventId", "it-devengo-" + UUID.randomUUID());
        cargo.put("creditAccountId", cuenta.toString());
        cargo.put("chargeType", "ORDINARY_INTEREST");
        cargo.put("totalAmount", 13.33);
        cargo.put("effectiveDate", "2026-11-10");
        kafkaTemplate.send("charges.charge-applied", cuenta.toString(), cargo);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForList("SELECT value_date FROM credit_portfolio.balance_events "
                                             + "WHERE credit_account_id = ?", LocalDate.class, cuenta))
                        .containsExactly(LocalDate.of(2026, 11, 10)));
    }

    @Test
    @DisplayName("rendimiento: la mora de 300 cuentas y el aviso, en segundos")
    void rendimiento() {
        List<UUID> cuentas = new ArrayList<>();
        for (int i = 0; i < 300; i++) cuentas.add(cuentaActiva("15000"));
        LocalDate d = LocalDate.of(2026, 11, 25);

        jdbc.update("""
                INSERT INTO credit_portfolio.charges_day_accruals
                       (business_date, ordinary_charges, ordinary_amount, moratorium_charges, moratorium_amount)
                VALUES (?, 0, 0, 0, 0)""", d);

        long inicio = System.nanoTime();
        foto(d);
        assertThat(runner.close(d, "prueba").outcome()).isEqualTo(DayCloseRunner.Outcome.CLOSED);
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        int total = dayCloses.find(d).orElseThrow().accounts();
        System.out.printf("[rendimiento] cierre de cartera (compuertas incluidas): %d cuentas en %d ms (%.0f cuentas/s)%n",
                total, ms, total * 1000.0 / Math.max(ms, 1));
        assertThat(total).isGreaterThanOrEqualTo(cuentas.size());
        assertThat(ms).as("ms para cerrar el día").isLessThan(30_000L);
    }

    /** El aviso a closing de ese día, tal como viajó. */
    private String avisoACierre(LocalDate dia) {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            for (var r : KafkaTestUtils.getRecords(avisos, Duration.ofSeconds(2))) {
                if (dia.toString().equals(r.key())) return r.value();
            }
        }
        throw new AssertionError("sin aviso a closing del " + dia);
    }

    private Map<String, Object> releaseOf(LocalDate dia, UUID cuenta) {
        var filas = jdbc.queryForList("""
                SELECT * FROM credit_portfolio.account_day_releases
                 WHERE business_date = ? AND credit_account_id = ?""", dia, cuenta);
        return filas.isEmpty() ? null : filas.get(0);
    }

    /** Las llaves (cuentas) de todo lo que cartera soltó a closing. */
    private List<String> released() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("lector-" + UUID.randomUUID(), "false", broker);
        props.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(c, "credit-portfolio.account-day-closed");
            List<String> out = new java.util.ArrayList<>();
            KafkaTestUtils.getRecords(c, Duration.ofSeconds(3)).forEach(r -> out.add(r.key()));
            return out;
        }
    }

    private boolean avisoACierreDe(LocalDate dia) {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            // La llave del aviso es el día: así closing recibe en orden los avisos de un mismo día.
            for (var r : KafkaTestUtils.getRecords(avisos, Duration.ofSeconds(2))) {
                if (dia.toString().equals(r.key()) && r.value().contains("businessDate")) return true;
            }
        }
        return false;
    }

    private static Map<String, Object> avisoDeCargos(LocalDate dia, int ordinarios, String importe) {
        return avisoDeCargos(dia, ordinarios, importe, List.of());
    }

    private static Map<String, Object> avisoDeCargos(LocalDate dia, int ordinarios, String importe, List<UUID> fuera) {
        Map<String, Object> m = new HashMap<>();
        m.put("heldAccounts", fuera.stream().map(UUID::toString).toList());
        m.put("eventId", UUID.randomUUID().toString());
        m.put("businessDate", dia.toString());
        m.put("ordinarySchedules", ordinarios);
        m.put("ordinaryErrors", 0);
        m.put("moratoriumSchedules", 0);
        m.put("moratoriumErrors", 0);
        m.put("ordinaryCharges", ordinarios);
        m.put("ordinaryAmount", new BigDecimal(importe));
        m.put("moratoriumCharges", 0);
        m.put("moratoriumAmount", BigDecimal.ZERO);
        return m;
    }

    private String interes(UUID cuenta, LocalDate dia, String importe) {
        return interes(cuenta, dia, importe, "it-devengo-" + UUID.randomUUID());
    }

    /** Un interés ordinario del cierre de ese día, como lo publica cargos. */
    private String interes(UUID cuenta, LocalDate dia, String importe, String id) {
        Map<String, Object> cargo = new HashMap<>();
        cargo.put("eventId", id);
        cargo.put("creditAccountId", cuenta.toString());
        cargo.put("chargeType", "ORDINARY_INTEREST");
        cargo.put("totalAmount", new BigDecimal(importe));
        cargo.put("effectiveDate", dia.toString());
        cargo.put("dayCloseDate", dia.toString());
        kafkaTemplate.send("charges.charge-applied", cuenta.toString(), cargo);
        return id;
    }

    private int interesesDe(LocalDate dia) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM credit_portfolio.balance_events
                 WHERE value_date = ? AND event_type = 'CHARGE_ORDINARY_INTEREST'""", Integer.class, dia);
    }

    /** The portfolio's snapshot of the day: charges accrues on top of it, and the day close requires it. */
    private void foto(LocalDate d) {
        snapshots.take(d, "test");
    }

    private UUID cuentaActiva(String principal) {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), UUID.randomUUID().toString().substring(0, 18), UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal(principal), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal(principal));
        accountRepository.save(a);
        return a.getCreditAccountId();
    }
}
