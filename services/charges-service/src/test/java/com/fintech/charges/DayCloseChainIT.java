package com.fintech.charges;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.service.dayclose.AccrualDayMonitor;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.infrastructure.job.DayCloseAccrualJob;
import com.fintech.charges.infrastructure.job.DayCloseAccrualJob.Outcome;
import com.fintech.charges.support.FakePortfolioDaySnapshots;
import com.fintech.charges.support.PortfolioSnapshotTestConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El cierre de cargos por cuenta, contra Postgres y un broker reales: compuertas, reparto, cuarentena,
 * retención y recuperación tardía.
 *
 * <p>Las fechas son de 2031 —el soporte de pruebas deja cerrar días futuros— para no cruzarse con los
 * calendarios que siembran otras pruebas del mismo contexto.
 */
@SpringBootTest(properties = {
        "fintech.day-close.allow-future-dates=true",
        "fintech.day-close.gate-wait=PT2S",
        "fintech.day-close.gate-poll=PT0.1S",
        "fintech.day-close.reannounce-after=PT0S",
        "fintech.day-close.units.retry-backoff=PT0S",
        "fintech.day-close.units.unit-wait=PT60S",
        "fintech.day-close.units.lease=PT30S"
})
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, topics = {"charges.charge-applied", "charges.charge-reversed", "charges.day-accrued",
        "charges.account-accrued", "credit-portfolio.day-closed", "credit-portfolio.balance-updated",
        "credit-portfolio.delinquency-status-updated", "closing.cutoff-run-completed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Import(PortfolioSnapshotTestConfig.class)
class DayCloseChainIT {

    private static final LocalDate D1 = LocalDate.of(2031, 5, 10);
    private static final LocalDate D2 = D1.plusDays(1);
    private static final LocalDate D3 = D1.plusDays(2);

    @Autowired DayCloseAccrualJob job;
    @Autowired AccrualDayMonitor monitor;
    @Autowired AccrualUnitStore units;
    @Autowired AccrualScheduleRepository schedules;
    @Autowired FakePortfolioDaySnapshots portfolio;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;

    @BeforeEach
    @AfterEach
    void clean() {
        dropRejection();
        portfolio.reset();
        jdbc.update("DELETE FROM charges.account_day_snapshots");
        jdbc.update("DELETE FROM charges.accrual_units");
        jdbc.update("DELETE FROM charges.accrual_days");
        jdbc.update("DELETE FROM charges.portfolio_day_closes");
        jdbc.update("DELETE FROM charges.cutoff_completions");
        jdbc.update("DELETE FROM charges.charge_records WHERE accrual_date >= ?", D1);
        jdbc.update("DELETE FROM charges.accrual_schedules");
    }

    @Test
    @DisplayName("el día se reparte por cuenta, cierra y avisa con cifras que cuadran; sus cargos ya están en el broker")
    void announcesWithControlTotals() {
        seed(20);

        var r = job.closeDay(D1, "prueba");

        assertThat(r.outcome()).isEqualTo(Outcome.CLOSED);
        JsonNode aviso = lastAnnouncement(D1);
        assertThat(aviso.get("ordinaryCharges").asInt()).isEqualTo(count("ORDINARY_INTEREST", D1)).isEqualTo(20);
        assertThat(aviso.get("ordinaryAmount").decimalValue()).isEqualByComparingTo(sum("ORDINARY_INTEREST", D1));
        assertThat(aviso.get("moratoriumCharges").asInt()).isEqualTo(count("MORATORIUM_INTEREST", D1)).isEqualTo(5);
        assertThat(aviso.get("accounts").asInt()).isEqualTo(20);
        assertThat(aviso.get("heldAccounts")).isEmpty();
        assertThat(units.counts(D1).done()).isEqualTo(20);

        // El aviso sale después de que el broker confirmó cada cargo del día: no hay uno sin publicar.
        Set<String> publicados = publishedIds();
        List<String> delDia = jdbc.queryForList(
                "SELECT charge_id::text FROM charges.charge_records WHERE accrual_date = ?", String.class, D1);
        assertThat(publicados).containsAll(delDia);
    }

    @Test
    @DisplayName("the day accrues on the portfolio's snapshot of D, not on the live balance, and the charge keeps that basis")
    void accruesOnTheSnapshotBasis() {
        UUID cuenta = seedOne();
        // The schedule holds 10,000 (a disposition landed after D); at the end of D the portfolio saw 4,000.
        portfolio.principalOf(cuenta, "4000.00");

        assertThat(job.closeDay(D1, "prueba").outcome()).isEqualTo(Outcome.CLOSED);

        // 4,000 × 36 % / 360 = 4.00, not the 10.00 the live balance would give.
        assertThat(sum("ORDINARY_INTEREST", D1)).isEqualByComparingTo("4.00");
        var detalle = units.detail(D1, cuenta).orElseThrow();
        assertThat(detalle.basis().principal()).isEqualByComparingTo("4000.00");
        assertThat(jdbc.queryForObject("""
                SELECT basis FROM charges.charge_records
                 WHERE credit_account_id = ? AND charge_type = 'ORDINARY_INTEREST' AND accrual_date = ?""",
                BigDecimal.class, cuenta, D1)).isEqualByComparingTo("4000.00");
    }

    @Test
    @DisplayName("an interest-free deferred purchase does not accrue: the basis is the snapshot's interest-bearing principal")
    void interestFreeDeferralDoesNotAccrue() {
        UUID cuenta = seedOne();
        // 10,000 owed, 6,000 of it a purchase at meses sin intereses with its installments still to come.
        portfolio.interestBearingOf(cuenta, "4000.00");

        assertThat(job.closeDay(D1, "prueba").outcome()).isEqualTo(Outcome.CLOSED);

        assertThat(sum("ORDINARY_INTEREST", D1)).isEqualByComparingTo("4.00");
        assertThat(units.detail(D1, cuenta).orElseThrow().basis().principal()).isEqualByComparingTo("4000.00");
        assertThat(jdbc.queryForObject("""
                SELECT principal FROM charges.account_day_snapshots WHERE credit_account_id = ? AND business_date = ?""",
                BigDecimal.class, cuenta, D1)).as("the copy keeps the whole principal").isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("an account the portfolio did not photograph is quarantined when the day is planned, and shows among the accounts in trouble")
    void quarantinesAnAccountMissingFromTheSnapshot() {
        seed(3);
        UUID fuera = seedOne();
        portfolio.omit(fuera);

        assertThat(job.closeDay(D1, "prueba").outcome()).isEqualTo(Outcome.CLOSED);

        assertThat(units.counts(D1).quarantined()).isEqualTo(1);
        assertThat(units.counts(D1).done()).isEqualTo(3);
        assertThat(units.inTrouble(10)).anySatisfy(t -> {
            assertThat(t.creditAccountId()).isEqualTo(fuera);
            assertThat(t.reason()).contains("foto");
        });
        assertThat(lastAnnouncement(D1).get("heldAccounts")).hasSize(1);
        assertThat(count("ORDINARY_INTEREST", D1)).isEqualTo(3);
    }

    @Test
    @DisplayName("while the portfolio cannot take the snapshot the day waits and nothing accrues; once it can, the day closes")
    void waitsForTheSnapshot() {
        seed(2);
        portfolio.unavailable("falta cerrar el " + D1.minusDays(1) + " en cartera");

        var espera = job.closeDay(D1, "prueba");

        assertThat(espera.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(espera.detail()).contains("cartera no ha tomado la foto del " + D1);
        assertThat(count("ORDINARY_INTEREST", D1)).isZero();
        assertThat(units.counts(D1).total()).isZero();

        portfolio.available();
        assertThat(job.closeDay(D1, "prueba").outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(count("ORDINARY_INTEREST", D1)).isEqualTo(2);
    }

    @Test
    @DisplayName("el día siguiente espera a que cartera cierre el anterior y closing termine su corte; con los dos, sigue")
    void waitsForPortfolioAndCutoff() {
        seed(5);
        assertThat(job.closeDay(D1, "prueba").outcome()).isEqualTo(Outcome.CLOSED);

        var espera = job.closeDay(D2, "prueba");

        assertThat(espera.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(espera.detail()).contains("cartera no ha cerrado el " + D1);
        assertThat(count("ORDINARY_INTEREST", D2)).as("no devengó sobre una mora sin calcular").isZero();

        portfolioClosed(D1);
        var corte = job.closeDay(D2, "prueba");
        assertThat(corte.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(corte.detail()).contains("closing no ha terminado el corte del " + D1);

        cutoffCompleted(D1);
        assertThat(job.closeDay(D2, "prueba").outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(count("ORDINARY_INTEREST", D2)).isEqualTo(5);
    }

    @Test
    @DisplayName("no se salta un día: el 12 no arranca si falta el 11")
    void doesNotSkipADay() {
        seed(3);
        job.closeDay(D1, "prueba");

        var r = job.closeDay(D3, "prueba");

        assertThat(r.outcome()).isEqualTo(Outcome.WAITING);
        assertThat(r.detail()).contains("faltan los cargos del " + D2);
        assertThat(count("ORDINARY_INTEREST", D3)).isZero();
    }

    /**
     * Lo del 31 de octubre, por cuenta: la tabla rechaza el IVA de una cuenta. Antes detenía el día entero;
     * ahora esa cuenta agota sus intentos, queda en cuarentena con su error, y el día cierra con las demás.
     * Sus cargos no salen —lo que no se guarda no sale—. Corregido el dato, se reintenta y devenga tarde.
     */
    @Test
    @DisplayName("una cuenta que no puede devengar queda en cuarentena con su error y el día cierra sin ella; al corregir, devenga tarde")
    void quarantinesTheFailingAccount() {
        seed(3);
        UUID cuenta = seedOne();
        rejectIvaOf(cuenta);

        var r = job.closeDay(D1, "prueba");

        assertThat(r.outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(r.detail()).contains("1 cuentas fuera del lote");
        JsonNode aviso = lastAnnouncement(D1);
        assertThat(aviso.get("ordinaryCharges").asInt()).isEqualTo(3);
        assertThat(aviso.get("heldAccounts")).extracting(JsonNode::asText).containsExactly(cuenta.toString());
        Map<String, Object> unidad = unit(D1, cuenta);
        assertThat(unidad).containsEntry("status", "QUARANTINED").containsEntry("attempts", 3);
        assertThat((String) unidad.get("last_error")).contains("rechazo de prueba");
        assertThat(publishedAccounts()).as("lo que no se guarda no sale").doesNotContain(cuenta.toString());
        assertThat(units.inTrouble(10)).extracting(AccrualUnitStore.Trouble::creditAccountId).containsExactly(cuenta);

        dropRejection();
        assertThat(units.retryQuarantined(D1, cuenta)).isEqualTo(1);
        tickUntil(() -> !accountAccrued(D1, cuenta).isEmpty());

        assertThat(unit(D1, cuenta)).containsEntry("status", "DONE").containsEntry("late", true);
        assertThat(accountAccrued(D1, cuenta)).singleElement()
                .satisfies(m -> assertThat(m.get("ordinaryCharges").asInt()).isEqualTo(1));
        assertThat(chargeTypesOf(cuenta)).containsExactlyInAnyOrder("ORDINARY_INTEREST", "IVA");
        // El aviso del día no cambia al volver a mandarlo: la cuenta sigue fuera de ese lote.
        assertThat(units.heldAccounts(D1)).containsExactly(cuenta);
    }

    @Test
    @DisplayName("una cuenta en cuarentena el 10 nace retenida el 11; al recuperarse devenga el 10 y después el 11, en ese orden")
    void heldAccountKeepsItsOwnOrder() {
        seed(2);
        UUID cuenta = seedOne();
        rejectIvaOf(cuenta);
        assertThat(job.closeDay(D1, "prueba").outcome()).isEqualTo(Outcome.CLOSED);
        portfolioClosed(D1);
        cutoffCompleted(D1);

        var r = job.closeDay(D2, "prueba");

        assertThat(r.outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(unit(D2, cuenta)).containsEntry("status", "HELD");
        assertThat((String) unit(D2, cuenta).get("held_reason")).contains("falta cerrar su día " + D1);
        assertThat(lastAnnouncement(D2).get("heldAccounts")).extracting(JsonNode::asText).containsExactly(cuenta.toString());
        assertThat(count("ORDINARY_INTEREST", D2)).isEqualTo(2);

        dropRejection();
        units.retryQuarantined(D1, cuenta);
        tickUntil(() -> !accountAccrued(D2, cuenta).isEmpty());

        List<ConsumerRecord<String, String>> tardios = read("charges.account-accrued").stream()
                .filter(m -> cuenta.toString().equals(m.key())).toList();
        assertThat(tardios).extracting(m -> dateOf(parse(m.value()).get("businessDate")))
                .containsExactly(D1, D2);
        assertThat(unit(D2, cuenta)).containsEntry("status", "DONE").containsEntry("late", true);
    }

    @Test
    @DisplayName("si cartera no confirma, se vuelve a avisar y se reenvían los cargos del día con su mismo id")
    void republishesWhatWasNotConfirmed() {
        seed(4);
        job.closeDay(D1, "prueba");
        int antes = publishedIdsWithRepeats().size();

        job.advance("prueba");

        List<String> despues = publishedIdsWithRepeats();
        int delDia = jdbc.queryForObject("SELECT count(*) FROM charges.charge_records WHERE accrual_date = ?",
                Integer.class, D1);
        assertThat(despues).hasSize(antes + delDia);
        assertThat(new HashSet<>(despues)).hasSize(antes);   // mismos ids: cartera los ignora si ya los tenía
        assertThat(jdbc.queryForObject("SELECT republished_at IS NOT NULL FROM charges.accrual_days WHERE business_date = ?",
                Boolean.class, D1)).isTrue();
    }

    @Test
    @DisplayName("rendimiento: 2 000 cuentas repartidas en 12 particiones y 4 hilos devengan, publican y avisan en menos de 60 s")
    void performance() {
        seed(2_000);
        long inicio = System.nanoTime();

        var r = job.closeDay(D1, "prueba");

        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertThat(r.outcome()).isEqualTo(Outcome.CLOSED);
        assertThat(count("ORDINARY_INTEREST", D1)).isEqualTo(2_000);
        System.out.printf("[rendimiento] cierre de cargos por cuenta: 2000 cuentas en %d ms (%.0f cuentas/s)%n",
                ms, 2_000 * 1000.0 / Math.max(ms, 1));
        assertThat(ms).isLessThan(60_000L);
    }

    /** {@code n} calendarios; uno de cada cuatro con capital vencido, que devenga moratorio. */
    private void seed(int n) {
        for (int i = 0; i < n; i++) {
            AccrualSchedule s = AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN",
                    "INSTALLMENT", new BigDecimal("0.36000000"), new BigDecimal("0.54000000"),
                    new BigDecimal("10000.00"), new BigDecimal("10000.00"), 3);
            if (i % 4 == 0) {
                s.updateMoratorium(new BigDecimal("900.00"), D1.minusDays(10), D1.minusDays(1), 3);
            }
            schedules.save(s);
        }
    }

    private UUID seedOne() {
        AccrualSchedule s = AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN",
                "INSTALLMENT", new BigDecimal("0.36000000"), new BigDecimal("0.54000000"),
                new BigDecimal("10000.00"), new BigDecimal("10000.00"), 3);
        schedules.save(s);
        return s.getCreditAccountId();
    }

    /** La tabla rechaza el IVA de una cuenta al guardar, como el check rechazó los IVA en cero. */
    private void rejectIvaOf(UUID cuenta) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION charges.reject_test_iva() RETURNS trigger AS $$
                BEGIN
                    IF NEW.credit_account_id = '%s' AND NEW.charge_type = 'IVA' THEN
                        RAISE EXCEPTION 'rechazo de prueba';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(cuenta));
        jdbc.execute("""
                CREATE TRIGGER reject_test_iva BEFORE INSERT ON charges.charge_records
                FOR EACH ROW EXECUTE FUNCTION charges.reject_test_iva()""");
    }

    private void dropRejection() {
        jdbc.execute("DROP TRIGGER IF EXISTS reject_test_iva ON charges.charge_records");
        jdbc.execute("DROP FUNCTION IF EXISTS charges.reject_test_iva()");
    }

    private void portfolioClosed(LocalDate d) {
        kafka.send("credit-portfolio.day-closed", d.toString(),
                Map.of("businessDate", d.toString(), "delinquencyAsOf", d.plusDays(1).toString()));
        await(() -> jdbc.queryForObject("SELECT count(*) FROM charges.portfolio_day_closes WHERE business_date = ?",
                Integer.class, d) == 1);
    }

    private void cutoffCompleted(LocalDate d) {
        kafka.send("closing.cutoff-run-completed", UUID.randomUUID().toString(),
                Map.of("businessDate", d.toString(), "status", "SEALED"));
        await(() -> jdbc.queryForObject("SELECT count(*) FROM charges.cutoff_completions WHERE business_date = ?",
                Integer.class, d) == 1);
    }

    private Map<String, Object> unit(LocalDate d, UUID cuenta) {
        return jdbc.queryForMap("SELECT * FROM charges.accrual_units WHERE business_date = ? AND credit_account_id = ?",
                d, cuenta);
    }

    private List<String> chargeTypesOf(UUID cuenta) {
        List<String> tipos = new ArrayList<>();
        for (ConsumerRecord<String, String> m : read("charges.charge-applied")) {
            if (cuenta.toString().equals(m.key())) tipos.add(parse(m.value()).get("chargeType").asText());
        }
        return tipos;
    }

    private List<JsonNode> accountAccrued(LocalDate d, UUID cuenta) {
        List<JsonNode> out = new ArrayList<>();
        for (ConsumerRecord<String, String> m : read("charges.account-accrued")) {
            JsonNode n = parse(m.value());
            if (cuenta.toString().equals(m.key()) && d.equals(dateOf(n.get("businessDate")))) out.add(n);
        }
        return out;
    }

    /** Mueve el monitor —en las pruebas no corre solo— hasta que se cumpla la condición. */
    private void tickUntil(java.util.function.BooleanSupplier condicion) {
        long limite = System.currentTimeMillis() + 30_000;
        while (!condicion.getAsBoolean()) {
            if (System.currentTimeMillis() > limite) throw new AssertionError("no se cumplió a tiempo");
            monitor.tick();
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private int count(String tipo, LocalDate d) {
        return jdbc.queryForObject("SELECT count(*) FROM charges.charge_records WHERE charge_type = ? AND accrual_date = ?",
                Integer.class, tipo, d);
    }

    private BigDecimal sum(String tipo, LocalDate d) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM charges.charge_records WHERE charge_type = ? AND accrual_date = ?",
                BigDecimal.class, tipo, d);
    }

    private JsonNode lastAnnouncement(LocalDate d) {
        JsonNode ultimo = null;
        for (ConsumerRecord<String, String> r : read("charges.day-accrued")) {
            if (d.toString().equals(r.key())) ultimo = parse(r.value());
        }
        assertThat(ultimo).as("aviso del " + d).isNotNull();
        return ultimo;
    }

    private Set<String> publishedAccounts() {
        Set<String> cuentas = new HashSet<>();
        for (ConsumerRecord<String, String> r : read("charges.charge-applied")) cuentas.add(r.key());
        return cuentas;
    }

    private Set<String> publishedIds() {
        return new HashSet<>(publishedIdsWithRepeats());
    }

    private List<String> publishedIdsWithRepeats() {
        List<String> ids = new ArrayList<>();
        for (ConsumerRecord<String, String> r : read("charges.charge-applied")) {
            ids.add(parse(r.value()).get("eventId").asText());
        }
        return ids;
    }

    /** Todo lo que tiene el tópico hasta ahora, de principio a fin. */
    private List<ConsumerRecord<String, String>> read(String topico) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("lector-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> c = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(c, topico);
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            KafkaTestUtils.getRecords(c, Duration.ofSeconds(3)).records(topico).forEach(out::add);
            int vacias = 0;
            while (vacias < 2) {
                var mas = c.poll(Duration.ofMillis(500));
                if (mas.isEmpty()) vacias++;
                mas.records(topico).forEach(out::add);
            }
            return out;
        }
    }

    /** El serializador de Kafka manda las fechas como {@code [2031,5,10]}; los consumidores aceptan las dos formas. */
    private static LocalDate dateOf(JsonNode n) {
        return n.isArray() ? LocalDate.of(n.get(0).asInt(), n.get(1).asInt(), n.get(2).asInt()) : LocalDate.parse(n.asText());
    }

    private JsonNode parse(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(java.util.function.BooleanSupplier condicion) {
        long limite = System.currentTimeMillis() + 30_000;
        while (!condicion.getAsBoolean()) {
            if (System.currentTimeMillis() > limite) throw new AssertionError("no se cumplió a tiempo");
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
