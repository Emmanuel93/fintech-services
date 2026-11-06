package com.fintech.closing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.closing.application.port.out.CutoffUnitStore;
import com.fintech.closing.application.service.cutoff.CutoffRunMonitor;
import com.fintech.closing.application.service.cutoff.CutoffRunService;
import com.fintech.closing.application.service.cutoff.CutoffRunService.CutoffRunView;
import com.fintech.closing.application.service.phase.CutoffPhaseProcessor;
import com.fintech.closing.domain.CloseUnit;
import com.fintech.closing.support.FakePortfolioDaySnapshots;
import com.fintech.closing.support.PortfolioSnapshotTestConfig;
import com.fintech.shared.testing.AbstractIntegrationTest;
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
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * El corte repartido por cuenta, de punta a punta: planificar, despachar por Kafka, cortar en varios
 * hilos consumidores, cerrar la corrida.
 *
 * <p>Corre contra Postgres, Redis y un broker reales (embebido), con el tópico de trabajo en 12
 * particiones y 4 hilos: es el reparto de verdad, no un mock del reparto.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "fintech.closing.cutoff.job-enabled=false",
        "fintech.closing.cutoff.allow-future-dates=true",
        "fintech.closing.cutoff.concurrency=4",
        "fintech.closing.cutoff.partitions=12",
        "fintech.closing.cutoff.redispatch-after=PT1S",
        "fintech.closing.cutoff.gate-wait=PT2S",
        "fintech.closing.cutoff.gate-poll=PT0.2S"
})
@EmbeddedKafka(partitions = 12, topics = {
        "closing.cutoff-due", "closing.cutoff-closed", "closing.cutoff-run-planned", "closing.cutoff-run-completed",
        "credit-portfolio.day-closed", "credit-portfolio.balance-updated", "credit-portfolio.delinquency-status-updated",
        "credit-portfolio.credit-account-activated", "credit-portfolio.account-day-closed"})
@Import(PortfolioSnapshotTestConfig.class)
class DistributedCutoffIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("closing"); }

    /** El catálogo que siembran los changesets: sin políticas, ningún corte encuentra con qué cortar. */
    @Override protected List<String> preserve() {
        return List.of("business_calendars", "calendar_days", "close_cycle_policies");
    }

    @Autowired CutoffRunService cutoffs;
    @Autowired CutoffRunMonitor monitor;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.kafka.core.KafkaTemplate<String, Object> kafka;
    @MockitoSpyBean CutoffPhaseProcessor cutoffProcessor;
    @Autowired com.fintech.closing.application.service.AccountProjectionService projection;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listeners;
    @Autowired FakePortfolioDaySnapshots portfolio;

    private Consumer<String, String> events;

    @BeforeEach
    void listen() {
        portfolio.reset();
        Map<String, Object> props = KafkaTestUtils.consumerProps("prueba-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        events = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
        events.subscribe(List.of("closing.cutoff-closed", "closing.cutoff-run-planned", "closing.cutoff-run-completed"));
        // Hasta que el grupo tiene particiones asignadas, «latest» no está anclado a nada.
        waitUntil(() -> { events.poll(Duration.ofMillis(100)); return !events.assignment().isEmpty(); }, 30);
    }

    @AfterEach
    void close() {
        events.close();
        reset(cutoffProcessor);
    }

    @Test
    @DisplayName("cada cuenta que corta ese día entra una vez, se corta en algún hilo y la corrida sella")
    void elCorteSeRepartePorCuentaYSella() {
        LocalDate dia = LocalDate.of(2031, 3, 15);
        List<UUID> clientes = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 30);
        seed(dia, "CREDIT_CARD", "REVOLVING", 10);
        List<UUID> distribuidoras = seed(dia, "DISTRIBUTOR_LINE", "REVOLVING", 5);
        seedSettled(dia);                    // terminal: no entra
        seed(dia.plusDays(1), "PERSONAL_LOAN", "INSTALLMENT", 4);   // corta otro día: no entra

        CutoffRunView plan = cutoffs.plan(dia);

        assertThat(plan.plannedUnits()).as("45 cuentas cortan ese día").isEqualTo(45);
        assertThat(plan.breakdown()).extracting(CutoffUnitStore.Breakdown::holderKind, CutoffUnitStore.Breakdown::productType,
                        CutoffUnitStore.Breakdown::units)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("CUSTOMER", "PERSONAL_LOAN", 30),
                        org.assertj.core.groups.Tuple.tuple("CUSTOMER", "CREDIT_CARD", 10),
                        org.assertj.core.groups.Tuple.tuple("DISTRIBUTOR", "DISTRIBUTOR_LINE", 5));

        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 60);
        monitor.tick();

        CutoffRunView fin = cutoffs.view(dia).orElseThrow();
        assertThat(fin.status()).isEqualTo("SEALED");
        assertThat(fin.counts().done()).isEqualTo(45);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM closing.cutoff_schedules WHERE cutoff_date = ? AND status = 'SEALED'",
                Integer.class, dia)).isEqualTo(45);

        List<JsonNode> cerrados = drain("closing.cutoff-closed", 45);
        assertThat(cerrados).hasSize(45);
        assertThat(cerrados).allSatisfy(e -> assertThat(e.get("runId").asText()).isEqualTo(plan.runId().toString()));
        JsonNode distribuidora = cerrados.stream()
                .filter(e -> e.get("creditAccountId").asText().equals(distribuidoras.get(0).toString()))
                .findFirst().orElseThrow();
        assertThat(distribuidora.get("holderKind").asText()).isEqualTo("DISTRIBUTOR");
        assertThat(distribuidora.get("productBehavior").asText()).isEqualTo("REVOLVING");
        JsonNode cliente = cerrados.stream()
                .filter(e -> e.get("creditAccountId").asText().equals(clientes.get(0).toString()))
                .findFirst().orElseThrow();
        assertThat(cliente.get("holderKind").asText()).isEqualTo("CUSTOMER");
        assertThat(date(cliente.get("periodStart"))).as("primer ciclo: desde la activación").isEqualTo(dia.minusMonths(1));

        // Los hilos de verdad se repartieron el trabajo: más de un consumidor cortó cuentas.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT fencing_token) FROM closing.close_units WHERE status = 'DONE'", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("planificar dos veces no duplica, y un mensaje repetido no vuelve a cortar")
    void esIdempotente() {
        LocalDate dia = LocalDate.of(2031, 4, 15);
        seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 8);

        CutoffRunView uno = cutoffs.plan(dia);
        CutoffRunView dos = cutoffs.plan(dia);
        assertThat(dos.runId()).isEqualTo(uno.runId());
        assertThat(dos.plannedUnits()).isEqualTo(8);

        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 60);
        drain("closing.cutoff-closed", 8);

        // Se fuerza un reenvío de todo: los mensajes nuevos encuentran las unidades terminadas.
        jdbcTemplate.update("UPDATE closing.close_units SET status = 'PENDING', dispatched_at = NULL "
                + "WHERE run_id = ? AND unit_key = (SELECT MIN(unit_key) FROM closing.close_units WHERE run_id = ?)",
                uno.runId(), uno.runId());
        cutoffs.plan(dia);
        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 60);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM closing.close_units WHERE run_id = ?", Integer.class, uno.runId())).isEqualTo(8);
        // El corte ya sellado no se reabre: la unidad repetida se omite («no le toca»), no corta dos veces.
        assertThat(drain("closing.cutoff-closed", 1)).as("ningún corte se publica dos veces").isEmpty();
        assertThat(cutoffs.view(dia).orElseThrow().counts().skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("una cuenta que falla se reintenta hasta el tope, la corrida no sella y el reintento manual la recupera")
    void lasFallidasNoSellanYSeReintentan() {
        LocalDate dia = LocalDate.of(2031, 5, 15);
        List<UUID> cuentas = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 6);
        UUID mala = cuentas.get(2);
        boolean[] arreglada = {false};
        doAnswer(inv -> {
            CloseUnit u = inv.getArgument(0);
            if (u.getCreditAccountId().equals(mala) && !arreglada[0]) throw new IllegalStateException("saldo ilegible");
            return inv.callRealMethod();
        }).when(cutoffProcessor).process(any(), any());

        cutoffs.plan(dia);
        // El monitor la vuelve a intentar hasta agotar los 3 intentos.
        waitUntil(() -> {
            monitor.tick();
            return !"RUNNING".equals(cutoffs.view(dia).orElseThrow().status());
        }, 60);

        CutoffRunView fallida = cutoffs.view(dia).orElseThrow();
        assertThat(fallida.status()).isEqualTo("FAILED");
        assertThat(fallida.counts().done()).isEqualTo(5);
        assertThat(fallida.failures()).singleElement().satisfies(f -> {
            assertThat(f.creditAccountId()).isEqualTo(mala);
            assertThat(f.attempts()).isEqualTo(3);
            assertThat(f.error()).contains("saldo ilegible");
        });

        arreglada[0] = true;
        cutoffs.retryFailed(dia);
        waitUntil(() -> {
            monitor.tick();
            return "SEALED".equals(cutoffs.view(dia).orElseThrow().status());
        }, 60);
        assertThat(cutoffs.view(dia).orElseThrow().counts().done()).isEqualTo(6);
    }

    @Test
    @DisplayName("el aviso de cartera de que cerró el día dispara el corte de ese día")
    void elAvisoDeCarteraDisparaElCorte() {
        LocalDate dia = LocalDate.of(2031, 8, 10);
        seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 3);

        kafka.send("credit-portfolio.day-closed", dia.toString(), Map.of(
                "businessDate", dia.toString(), "delinquencyAsOf", dia.plusDays(1).toString(),
                "accounts", 3, "errors", 0));

        waitUntil(() -> cutoffs.view(dia).map(v -> v.counts().open() == 0).orElse(false), 60);
        monitor.tick();   // en la prueba el monitor no corre solo
        assertThat(cutoffs.view(dia).orElseThrow().status()).isEqualTo("SEALED");
        assertThat(cutoffs.view(dia).orElseThrow().plannedUnits()).isEqualTo(3);
        assertThat(cutoffs.view(dia).orElseThrow().counts().done()).isEqualTo(3);
    }

    @Test
    @DisplayName("the days past due sealed are the portfolio's snapshot of D, not the projection's current value")
    void daysDelinquentComeFromTheSnapshot() {
        LocalDate dia = LocalDate.of(2031, 11, 30);
        UUID cuenta = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 1).get(0);
        // On the 30th the account was 15 days late; by the time the unit seals the customer has paid and
        // the projection says 0. The statement must show the 15.
        portfolio.daysDelinquentOf(cuenta, 15);
        jdbcTemplate.update("UPDATE closing.account_close_profiles SET days_delinquent = 0 WHERE credit_account_id = ?", cuenta);

        kafka.send("credit-portfolio.day-closed", dia.toString(), Map.of(
                "businessDate", dia.toString(), "delinquencyAsOf", dia.plusDays(1).toString(), "accounts", 1, "errors", 0));

        waitUntil(() -> cutoffs.view(dia).map(v -> v.counts().done() == 1).orElse(false), 60);
        assertThat(drain("closing.cutoff-closed", 1)).singleElement().satisfies(n -> {
            assertThat(n.get("creditAccountId").asText()).isEqualTo(cuenta.toString());
            assertThat(n.get("daysDelinquent").asInt()).isEqualTo(15);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT days_delinquent_at_day FROM closing.close_units WHERE credit_account_id = ?", Integer.class, cuenta))
                .isEqualTo(15);
    }

    @Test
    @DisplayName("a principal that disagrees with the portfolio's snapshot, or an account without one, fails the unit instead of sealing a wrong cutoff")
    void principalMismatchOrMissingSnapshotFailsTheUnit() {
        LocalDate dia = LocalDate.of(2031, 12, 10);
        List<UUID> cuentas = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 3);
        UUID desfasada = cuentas.get(0);
        UUID sinFoto = cuentas.get(1);
        portfolio.principalOf(desfasada, "9999.99");
        portfolio.omit(sinFoto);

        kafka.send("credit-portfolio.day-closed", dia.toString(), Map.of(
                "businessDate", dia.toString(), "delinquencyAsOf", dia.plusDays(1).toString(), "accounts", 3, "errors", 0));

        waitUntil(() -> cutoffs.view(dia).map(v -> v.counts().done() == 1 && v.counts().failed() == 2).orElse(false), 60);
        var fallas = cutoffs.view(dia).orElseThrow().failures();
        assertThat(fallas).extracting(f -> f.creditAccountId()).containsExactlyInAnyOrder(desfasada, sinFoto);
        assertThat(fallas).anySatisfy(f -> assertThat(f.error()).contains("no coincide con la foto"));
        assertThat(fallas).anySatisfy(f -> assertThat(f.error()).contains("no tiene foto"));
        assertThat(cutoffStatus(desfasada, dia)).isEqualTo("SCHEDULED");
        assertThat(cutoffStatus(cuentas.get(2), dia)).isEqualTo("SEALED");
    }

    @Test
    @DisplayName("una cuenta que cargos dejó fuera del cierre no se corta con las demás; al llegar su interés, se corta sola")
    void heldAccountWaitsForItsInterest() {
        LocalDate dia = LocalDate.of(2031, 10, 14);
        List<UUID> cuentas = seed(dia, "DISTRIBUTOR_LINE", "REVOLVING", 3);
        UUID retenida = cuentas.get(2);

        kafka.send("credit-portfolio.day-closed", dia.toString(), Map.of(
                "businessDate", dia.toString(), "delinquencyAsOf", dia.plusDays(1).toString(),
                "accounts", 3, "errors", 0, "heldAccounts", List.of(retenida.toString())));

        waitUntil(() -> cutoffs.view(dia).map(v -> v.counts().open() == 0 && v.counts().done() == 2).orElse(false), 60);
        monitor.tick();
        var corte = cutoffs.view(dia).orElseThrow();
        assertThat(corte.status()).as("el resto del corte termina; la retenida no lo detiene").isEqualTo("SEALED");
        assertThat(corte.counts().held()).isEqualTo(1);
        assertThat(cutoffStatus(retenida, dia)).isEqualTo("SCHEDULED");
        assertThat(drain("closing.cutoff-run-completed", 1)).singleElement()
                .satisfies(n -> assertThat(n.get("heldUnits").asInt()).isEqualTo(1));

        kafka.send("credit-portfolio.account-day-closed", retenida.toString(), Map.of(
                "businessDate", dia.toString(), "creditAccountId", retenida.toString()));

        waitUntil(() -> cutoffs.view(dia).map(v -> v.counts().done() == 3).orElse(false), 60);
        monitor.tick();
        assertThat(cutoffs.view(dia).orElseThrow().status()).isEqualTo("SEALED");
        assertThat(cutoffs.view(dia).orElseThrow().counts().held()).isZero();
        assertThat(cutoffStatus(retenida, dia)).isEqualTo("SEALED");
        assertThat(drain("closing.cutoff-closed", 3)).extracting(n -> n.get("creditAccountId").asText())
                .contains(retenida.toString());
    }

    @Test
    @DisplayName("si cartera suelta la cuenta antes de que se planifique el corte, la cuenta nace pendiente")
    void releaseBeforePlanning() {
        LocalDate dia = LocalDate.of(2031, 10, 15);
        UUID retenida = seed(dia, "DISTRIBUTOR_LINE", "REVOLVING", 1).get(0);
        jdbcTemplate.update("""
                INSERT INTO closing.portfolio_day_closes (business_date, delinquency_as_of, held_accounts)
                VALUES (?, ?, CAST(? AS uuid[]))""", dia, dia.plusDays(1), "{" + retenida + "}");
        jdbcTemplate.update("INSERT INTO closing.held_releases (business_date, credit_account_id) VALUES (?, ?)",
                dia, retenida);

        cutoffs.plan(dia);

        waitUntil(() -> cutoffs.view(dia).map(v -> v.counts().done() == 1).orElse(false), 60);
        assertThat(cutoffs.view(dia).orElseThrow().counts().held()).isZero();
    }

    @Test
    @DisplayName("un día sin cortes sella en el acto")
    void unDiaSinCortesSella() {
        LocalDate dia = LocalDate.of(2031, 6, 15);
        CutoffRunView plan = cutoffs.plan(dia);
        assertThat(plan.plannedUnits()).isZero();
        assertThat(plan.status()).isEqualTo("SEALED");
    }

    @Test
    @DisplayName("el job recoge los días que se quedaron sin correr, del más viejo al de ayer")
    void elJobRecogeLosDiasPendientes() {
        LocalDate hoy = LocalDate.of(2031, 7, 20);
        seed(hoy.minusDays(5), "PERSONAL_LOAN", "INSTALLMENT", 2);
        seed(hoy.minusDays(1), "CREDIT_CARD", "REVOLVING", 3);
        seed(hoy, "PERSONAL_LOAN", "INSTALLMENT", 4);   // hoy todavía no termina

        List<CutoffRunView> corridas = cutoffs.planDue(hoy);

        assertThat(corridas).extracting(CutoffRunView::businessDate).containsExactly(hoy.minusDays(5), hoy.minusDays(1));
        assertThat(corridas).extracting(CutoffRunView::plannedUnits).containsExactly(2, 3);
    }

    @Test
    @DisplayName("rendimiento: 5 000 cuentas se planifican en una sentencia y se cortan repartidas entre los hilos")
    void rendimiento() {
        LocalDate dia = LocalDate.of(2031, 8, 15);
        seedBulk(dia, 5_000);

        long t0 = System.nanoTime();
        CutoffRunView plan = cutoffs.plan(dia);
        long planMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(plan.plannedUnits()).isEqualTo(5_000);

        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 240);
        long corteMs = (System.nanoTime() - t0) / 1_000_000;
        monitor.tick();

        System.out.printf("[rendimiento] planificar+despachar 5000 = %d ms · cortar 5000 = %d ms (%.0f cuentas/s con 4 hilos)%n",
                planMs, corteMs, 5_000_000.0 / Math.max(corteMs, 1));
        assertThat(cutoffs.view(dia).orElseThrow().status()).isEqualTo("SEALED");
        // Lo serial del corte —planificar y despachar— no depende de cuántos pods haya.
        assertThat(planMs).as("planificar + despachar 5 000 (ms)").isLessThan(15_000L);
        assertThat(corteMs).as("cortar 5 000 cuentas con 4 hilos (ms)").isLessThan(200_000L);
    }

    @Test
    @DisplayName("sin el cierre de cartera del día, ni la operación a mano lo corta")
    void sinCierreDeCarteraNoSeCorta() {
        LocalDate dia = LocalDate.of(2031, 9, 10);
        seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cutoffs.planWhenReady(dia))
                .isInstanceOf(CutoffRunService.CutoffGateClosed.class)
                .hasMessageContaining("cartera no ha cerrado el " + dia);
        assertThat(cutoffs.advance("prueba")).extracting(CutoffRunView::businessDate).doesNotContain(dia);
        assertThat(cutoffs.view(dia)).isEmpty();
    }

    @Test
    @DisplayName("el corte sella el saldo al cierre de D: el interés de D entra, el pago de D+1 no, aunque ya llegó")
    void selloAlCierreDelDia() {
        LocalDate dia = LocalDate.of(2031, 9, 12);
        UUID cuenta = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 1).get(0);
        // El devengo de D, aplicado a las 00:05 de D+1, y un pago de las 00:07 de D+1: los dos antes del corte.
        projection.onBalanceUpdated(cuenta, new BigDecimal("10000.00"), new BigDecimal("10463.33"), "ACTIVE", 1, null, dia);
        projection.onBalanceUpdated(cuenta, new BigDecimal("9000.00"), new BigDecimal("9463.33"), "ACTIVE", 2, null, dia.plusDays(1));

        cutoffs.plan(dia);
        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 60);

        var sello = jdbcTemplate.queryForMap("""
                SELECT balance_at_cutoff, principal_at_cutoff FROM closing.cutoff_schedules
                 WHERE credit_account_id = ? AND cutoff_date = ?""", cuenta, dia);
        assertThat((BigDecimal) sello.get("balance_at_cutoff")).isEqualByComparingTo("10463.33");
        assertThat((BigDecimal) sello.get("principal_at_cutoff")).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("una cuenta liquidada el día siguiente sí corta (con su saldo de antes del pago); liquidada ese día, no")
    void laLiquidadaAlDiaSiguienteCorta() {
        LocalDate dia = LocalDate.of(2031, 9, 14);
        List<UUID> cuentas = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 2);
        projection.onBalanceUpdated(cuentas.get(0), BigDecimal.ZERO, BigDecimal.ZERO, "SETTLED", 1, null, dia.plusDays(1));
        projection.onBalanceUpdated(cuentas.get(1), BigDecimal.ZERO, BigDecimal.ZERO, "SETTLED", 1, null, dia);

        CutoffRunView plan = cutoffs.plan(dia);
        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 60);

        assertThat(plan.plannedUnits()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT balance_at_cutoff FROM closing.cutoff_schedules WHERE credit_account_id = ? AND cutoff_date = ?""",
                BigDecimal.class, cuentas.get(0), dia)).isEqualByComparingTo("10450.00");
    }

    @Test
    @DisplayName("si closing va atrás con los saldos de cartera, el corte espera; al ponerse al día, la reanudación corta")
    void esperaLosSaldos() {
        LocalDate dia = LocalDate.of(2031, 9, 16);
        UUID cuenta = seed(dia, "PERSONAL_LOAN", "INSTALLMENT", 1).get(0);
        var saldos = listeners.getListenerContainers().stream()
                .filter(c -> java.util.Arrays.asList(c.getContainerProperties().getTopics()).contains("credit-portfolio.balance-updated"))
                .findFirst().orElseThrow();
        saldos.stop();
        try {
            // El interés del día llega a Kafka pero closing todavía no lo consume.
            kafka.send("credit-portfolio.balance-updated", cuenta.toString(), Map.of(
                    "creditAccountId", cuenta.toString(), "principalBalance", 10000, "totalDebt", 10470,
                    "accountStatus", "ACTIVE", "balanceVersion", 1, "valueDate", dia.toString()));
            kafka.send("credit-portfolio.day-closed", dia.toString(), Map.of(
                    "businessDate", dia.toString(), "delinquencyAsOf", dia.plusDays(1).toString(),
                    "accounts", 1, "errors", 0));

            waitUntil(() -> jdbcTemplate.queryForList("SELECT waiting_for FROM closing.portfolio_day_closes WHERE business_date = ?",
                    String.class, dia).stream().anyMatch(w -> w != null && w.contains("movimientos de cartera por consumir")), 30);
            assertThat(cutoffs.view(dia)).as("no se cortó con el saldo viejo").isEmpty();
        } finally {
            saldos.start();
        }

        waitUntil(() -> !cutoffs.advance("reanudación").isEmpty() || cutoffs.view(dia).isPresent(), 30);
        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 60);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT balance_at_cutoff FROM closing.cutoff_schedules WHERE credit_account_id = ? AND cutoff_date = ?""",
                BigDecimal.class, cuenta, dia)).isEqualByComparingTo("10470");
    }

    @Test
    @DisplayName("el aviso de fin de corte que no salió lo vuelve a mandar la reanudación")
    void reenviaElAvisoDeFin() {
        LocalDate dia = LocalDate.of(2031, 9, 18);
        jdbcTemplate.update("INSERT INTO closing.portfolio_day_closes (business_date, delinquency_as_of, accounts, errors) VALUES (?, ?, 0, 0)",
                dia, dia.plusDays(1));
        assertThat(cutoffs.advance("prueba")).extracting(CutoffRunView::businessDate).contains(dia);
        drain("closing.cutoff-run-completed", 1);
        jdbcTemplate.update("UPDATE closing.portfolio_day_closes SET completion_announced_at = NULL WHERE business_date = ?", dia);

        assertThat(cutoffs.announcePendingCompletions()).isEqualTo(1);

        assertThat(drain("closing.cutoff-run-completed", 1)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT completion_announced_at IS NOT NULL FROM closing.portfolio_day_closes WHERE business_date = ?",
                Boolean.class, dia)).isTrue();
    }

    @Test
    @DisplayName("rendimiento: 1 000 cuentas con movimientos posteriores al corte se sellan al cierre del día")
    void rendimientoDelSaldoAlCierre() {
        LocalDate dia = LocalDate.of(2031, 9, 20);
        seedBulk(dia, 1_000);
        jdbcTemplate.update("""
                INSERT INTO closing.balance_movements (credit_account_id, balance_version, value_date, principal_delta, debt_delta)
                SELECT c.credit_account_id, v, ?::date + v, -10, -10
                  FROM closing.cutoff_schedules c, generate_series(1, 5) v
                 WHERE c.cutoff_date = ?""", dia, dia);

        long t0 = System.nanoTime();
        cutoffs.plan(dia);
        waitUntil(() -> cutoffs.view(dia).orElseThrow().counts().open() == 0, 120);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("[rendimiento] corte al cierre del día: 1000 cuentas con 5 movimientos posteriores = %d ms%n", ms);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM closing.cutoff_schedules WHERE cutoff_date = ? AND balance_at_cutoff = 1050""",
                Integer.class, dia)).as("1000 de saldo + 50 que se restan de los cinco movimientos").isEqualTo(1_000);
        assertThat(ms).isLessThan(60_000L);
    }

    // ── siembra ──────────────────────────────────────────────────────────────────────────────────

    private List<UUID> seed(LocalDate cutoffDate, String productType, String behavior, int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID cuenta = UUID.randomUUID();
            ids.add(cuenta);
            jdbcTemplate.update("""
                    INSERT INTO closing.account_close_profiles
                           (credit_account_id, obligor_party_id, product_type, product_behavior, status,
                            activated_on, payment_frequency, term_periods, nominal_rate,
                            principal_balance, total_debt, updated_at)
                    VALUES (?, ?, ?, ?, 'ACTIVE', ?, 'MONTHLY', 12, 0.60, ?, ?, now())
                    """, cuenta, UUID.randomUUID(), productType, behavior, cutoffDate.minusMonths(1),
                    new BigDecimal("10000.00"), new BigDecimal("10450.00"));
            jdbcTemplate.update("""
                    INSERT INTO closing.cutoff_schedules (credit_account_id, cycle_number, cutoff_date, payment_due_date, status)
                    VALUES (?, 1, ?, ?, 'SCHEDULED')
                    """, cuenta, cutoffDate, cutoffDate.plusDays(10));
        }
        return ids;
    }

    private void seedSettled(LocalDate cutoffDate) {
        UUID cuenta = seed(cutoffDate, "PERSONAL_LOAN", "INSTALLMENT", 1).get(0);
        jdbcTemplate.update("UPDATE closing.account_close_profiles SET status = 'SETTLED' WHERE credit_account_id = ?", cuenta);
    }

    /** Veinte mil cuentas en dos sentencias: la siembra no debe ser lo que se mide. */
    private void seedBulk(LocalDate cutoffDate, int n) {
        jdbcTemplate.update("""
                INSERT INTO closing.account_close_profiles
                       (credit_account_id, obligor_party_id, product_type, product_behavior, status,
                        activated_on, payment_frequency, term_periods, principal_balance, total_debt, updated_at)
                SELECT gen_random_uuid(), gen_random_uuid(),
                       CASE WHEN g % 10 = 0 THEN 'DISTRIBUTOR_LINE' ELSE 'PERSONAL_LOAN' END,
                       CASE WHEN g % 10 = 0 THEN 'REVOLVING' ELSE 'INSTALLMENT' END,
                       'ACTIVE', ?, 'MONTHLY', 12, 1000, 1000, now()
                  FROM generate_series(1, ?) g
                """, cutoffDate.minusMonths(1), n);
        jdbcTemplate.update("""
                INSERT INTO closing.cutoff_schedules (credit_account_id, cycle_number, cutoff_date, payment_due_date, status)
                SELECT credit_account_id, 1, ?, ?, 'SCHEDULED'
                  FROM closing.account_close_profiles p
                 WHERE activated_on = ?
                   AND NOT EXISTS (SELECT 1 FROM closing.cutoff_schedules c WHERE c.credit_account_id = p.credit_account_id)
                """, cutoffDate, cutoffDate.plusDays(10), cutoffDate.minusMonths(1));
    }

    // ── utilidades ───────────────────────────────────────────────────────────────────────────────

    private String cutoffStatus(UUID cuenta, LocalDate dia) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM closing.cutoff_schedules WHERE credit_account_id = ? AND cutoff_date = ?",
                String.class, cuenta, dia);
    }

    private List<JsonNode> drain(String topic, int expected) {
        List<JsonNode> found = new ArrayList<>();
        long hasta = System.nanoTime() + TimeUnit.SECONDS.toNanos(expected == 0 ? 3 : 20);
        while (System.nanoTime() < hasta && found.size() < expected) {
            for (ConsumerRecord<String, String> r : events.poll(Duration.ofMillis(300))) {
                if (r.topic().equals(topic)) found.add(read(r.value()));
            }
        }
        return found;
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** El serializador de la plataforma escribe las fechas como arreglo [año, mes, día]. */
    private static LocalDate date(JsonNode node) {
        if (node.isArray()) return LocalDate.of(node.get(0).asInt(), node.get(1).asInt(), node.get(2).asInt());
        return LocalDate.parse(node.asText());
    }

    private static void waitUntil(BooleanSupplier condition, int seconds) {
        long hasta = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < hasta) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        throw new AssertionError("No se cumplió en " + seconds + " s");
    }
}
