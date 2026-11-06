package com.fintech.statement;

import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.statement.application.service.StatementSelfCheckService;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.application.port.out.StatementSource;
import com.fintech.statement.application.service.StatementBatchService;
import com.fintech.statement.application.service.StatementGenerationService;
import com.fintech.statement.application.service.StatementGenerationService.Outcome;
import com.fintech.statement.application.service.StatementRetryService;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import com.fintech.statement.support.Samples;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Del corte sellado al PDF descargable, por Kafka y contra Postgres: lo que pasa en producción con la
 * cartera simulada.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "fintech.statement.retry.enabled=false",
        "fintech.statement.concurrency=4",
        "fintech.statement.partitions=12"
})
@EmbeddedKafka(partitions = 12, topics = {
        "closing.cutoff-closed", "closing.cutoff-run-planned", "closing.cutoff-run-completed",
        "statement.statement-generated"})
@AutoConfigureMockMvc
class StatementGenerationIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("statement"); }

    @MockitoBean StatementSource source;
    @Autowired StatementBatchService batches;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired StatementRepository statements;
    @Autowired StatementGenerationService generation;
    @Autowired StatementRetryService retry;
    @Autowired StatementSelfCheckService selfChecks;
    @Autowired MockMvc mvc;

    private Consumer<String, String> generated;

    /** El mismo contrato que publica closing en {@code closing.cutoff-closed}. */
    record ClosingCutoff(String eventId, UUID runId, UUID creditAccountId, UUID obligorPartyId, String productType,
                         String productBehavior, String holderKind, int cycleNumber, LocalDate periodStart,
                         LocalDate cutoffDate, LocalDate paymentDueDate, BigDecimal balanceAtCutoff,
                         BigDecimal principalAtCutoff, BigDecimal interestAtCutoff, BigDecimal amountDue,
                         BigDecimal minimumPayment, int daysDelinquent) {}

    record RunPlanned(String eventId, UUID runId, LocalDate businessDate, int totalUnits, List<Line> breakdown) {
        record Line(String productType, String productBehavior, String holderKind, int units) {}
    }

    @BeforeEach
    void listen() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("prueba-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        generated = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        generated.subscribe(List.of("statement.statement-generated"));
        waitUntil(() -> { generated.poll(Duration.ofMillis(100)); return !generated.assignment().isEmpty(); }, 30);
    }

    @AfterEach
    void close() { generated.close(); }

    @Test
    @DisplayName("un corte de tres cuentas —plazo, revolvente, distribuidora— da tres PDF, cada uno con su forma")
    void corridaCompleta() throws Exception {
        UUID run = UUID.randomUUID();
        UUID plazo = UUID.randomUUID(), revolvente = UUID.randomUUID(), distribuidora = UUID.randomUUID();
        UUID titular = UUID.randomUUID(), cliente = UUID.randomUUID();
        when(source.view(eq(plazo), any(), any(), any())).thenReturn(Samples.plazo(plazo, titular));
        when(source.view(eq(revolvente), any(), any(), any())).thenReturn(Samples.revolvente(revolvente, titular));
        when(source.view(eq(distribuidora), any(), any(), any()))
                .thenReturn(Samples.distribuidora(distribuidora, titular, List.of(cliente)));
        when(source.parties(anyCollection())).thenReturn(Map.of(
                titular, Samples.persona(titular, "Laura Méndez Ortiz"),
                cliente, Samples.persona(cliente, "Mario Pérez Luna")));

        kafka.send("closing.cutoff-run-planned", run.toString(), new RunPlanned("e", run, Samples.CORTE, 3, List.of(
                new RunPlanned.Line("PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 1),
                new RunPlanned.Line("REVOLVING_LINE", "REVOLVING", "CUSTOMER", 1),
                new RunPlanned.Line("DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 1))));
        send(cutoff(run, plazo, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER"));
        send(cutoff(run, revolvente, titular, "REVOLVING_LINE", "REVOLVING", "CUSTOMER"));
        send(cutoff(run, distribuidora, titular, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR"));

        waitUntil(() -> count("GENERATED") == 3, 60);

        assertThat(statements.findByAccount(plazo, 5)).singleElement().satisfies(s -> {
            assertThat(s.layout()).isEqualTo(StatementLayout.INSTALLMENT);
            assertThat(s.templateVersion()).startsWith("estado-cuenta-prestamo-personal@");
        });
        assertThat(statements.findByAccount(revolvente, 5)).singleElement().satisfies(s -> {
            assertThat(s.layout()).isEqualTo(StatementLayout.REVOLVING);
            assertThat(s.templateVersion()).startsWith("estado-cuenta-linea-revolvente@");
            assertThat(s.contractNumber()).isEqualTo("CTR-202607-0042");
        });
        var dist = statements.findByAccount(distribuidora, 5).get(0);
        assertThat(dist.layout()).isEqualTo(StatementLayout.DISTRIBUTOR);
        assertThat(dist.templateVersion()).startsWith("estado-cuenta-linea-distribuidora@");
        // Principal of the installment it bills (833.33) plus what the line accrued up to the cutoff
        // (16,666.66 - 16,000 = 666.66): 1,499.99.
        assertThat(dist.amountDue()).isEqualByComparingTo("1499.99");

        mvc.perform(get("/api/v1/statements/{id}/pdf", dist.statementId()).header("X-User-Id", "backoffice"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("CTR-202609-0007")));
        // The batch comes from a different topic and consumer than the statements: it can land after them.
        waitUntil(() -> batches.progress(run).isPresent(), 30);
        mvc.perform(get("/api/v1/statements/batches/{run}", run).header("X-User-Id", "backoffice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expected").value(3))
                .andExpect(jsonPath("$.generated").value(3))
                .andExpect(jsonPath("$.notReceived").value(0));
        mvc.perform(get("/api/v1/statements/{id}/pdf", dist.statementId())).andExpect(status().isUnauthorized());

        assertThat(drain(3)).as("statement-generated, uno por documento").isEqualTo(3);

        // The statements check themselves: what the run left passes; a balance and a document broken by hand do not.
        SelfCheckReport clean = selfChecks.check();
        assertThat(clean.failures()).as("%s", clean.checks()).isEmpty();
        jdbcTemplate.update("UPDATE statement.statements SET balance_at_cutoff = balance_at_cutoff + 1 WHERE statement_id = ?",
                dist.statementId());
        jdbcTemplate.update("DELETE FROM statement.statement_documents WHERE statement_id = ?",
                statements.findByAccount(plazo, 5).get(0).statementId());
        assertThat(selfChecks.check().failures()).extracting(SelfCheck::code)
                .containsExactlyInAnyOrder("SALDO_DEL_CORTE", "DOCUMENTO_DE_CADA_ESTADO");
    }

    @Test
    @DisplayName("el mismo corte entregado dos veces da un solo documento y una sola consulta a cartera")
    void entregaRepetida() {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        when(source.view(eq(cuenta), any(), any(), any())).thenReturn(Samples.revolvente(cuenta, titular));
        when(source.parties(anyCollection())).thenReturn(Map.of());

        ClosingCutoff corte = cutoff(UUID.randomUUID(), cuenta, titular, "REVOLVING_LINE", "REVOLVING", "CUSTOMER");
        send(corte);
        waitUntil(() -> count("GENERATED") == 1, 30);
        send(corte);
        send(corte);
        sleep(2_000);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM statement.statements", Integer.class)).isEqualTo(1);
        verify(source, times(1)).view(eq(cuenta), any(), any(), any());
    }

    @Test
    @DisplayName("si cartera no responde queda FAILED con la causa, y el reintento lo genera con lo que dijo el corte")
    void fallaYSeReintenta() {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        when(source.view(eq(cuenta), any(), any(), any()))
                .thenThrow(new IllegalStateException("credit-portfolio: 503"))
                .thenReturn(Samples.plazo(cuenta, titular));
        when(source.parties(anyCollection())).thenReturn(Map.of());

        send(cutoff(UUID.randomUUID(), cuenta, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER"));
        waitUntil(() -> count("FAILED") == 1, 30);
        assertThat(statements.findByAccount(cuenta, 1).get(0).lastError()).contains("503");

        var r = retry.retry();

        assertThat(r.generated()).isEqualTo(1);
        var s = statements.findByAccount(cuenta, 1).get(0);
        assertThat(s.status().name()).isEqualTo("GENERATED");
        assertThat(s.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("un producto sin plantilla no sale con una genérica: queda FAILED con la causa y sin consultar a cartera")
    void productoSinPlantilla() {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();

        send(cutoff(UUID.randomUUID(), cuenta, titular, "STUDENT_LOAN", "INSTALLMENT", "CUSTOMER"));

        waitUntil(() -> statements.findByAccount(cuenta, 1).stream().anyMatch(s -> s.status().name().equals("FAILED")), 30);
        assertThat(statements.findByAccount(cuenta, 1).get(0).lastError())
                .contains("No hay plantilla de estado de cuenta para el producto STUDENT_LOAN");
        verify(source, times(0)).view(eq(cuenta), any(), any(), any());
    }

    @Test
    @DisplayName("ocho hilos piden el mismo documento: lo genera exactamente uno")
    void unSoloGenerador() throws Exception {
        UUID cuenta = UUID.randomUUID(), titular = UUID.randomUUID();
        when(source.view(eq(cuenta), any(), any(), any())).thenReturn(Samples.plazo(cuenta, titular));
        when(source.parties(anyCollection())).thenReturn(Map.of());
        CutoffFacts f = Samples.facts(cuenta, titular, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 1, "100", null, null);
        UUID id = statements.register(f, StatementLayout.INSTALLMENT);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Outcome>> tareas = new ArrayList<>();
        for (int i = 0; i < 8; i++) tareas.add(() -> generation.generate(id));
        List<Outcome> resultados = new ArrayList<>();
        for (var fut : pool.invokeAll(tareas)) resultados.add(fut.get());
        pool.shutdown();

        assertThat(resultados).filteredOn(o -> o == Outcome.GENERATED).hasSize(1);
        assertThat(resultados).filteredOn(o -> o == Outcome.ALREADY_TAKEN).hasSize(7);
    }

    @Test
    @DisplayName("rendimiento: 300 cortes por Kafka con 4 hilos se vuelven 300 PDF")
    void rendimiento() {
        UUID run = UUID.randomUUID(), titular = UUID.randomUUID();
        List<UUID> clientes = java.util.stream.IntStream.range(0, 10).mapToObj(i -> UUID.randomUUID()).toList();
        when(source.view(any(), any(), any(), any()))
                .thenAnswer(inv -> Samples.distribuidora(inv.getArgument(0), titular, clientes));
        when(source.parties(anyCollection())).thenReturn(Map.of());

        long t0 = System.nanoTime();
        for (int i = 0; i < 300; i++) {
            send(cutoff(run, UUID.randomUUID(), titular, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR"));
        }
        waitUntil(() -> count("GENERATED") == 300, 180);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[rendimiento] 300 estados de cuenta en %d ms (%.1f PDF/s con 4 hilos)%n", ms, 300_000.0 / ms);
        assertThat(ms).isLessThan(120_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT generated_by) FROM statement.statements", Integer.class)).isEqualTo(1);

        long checkStarted = System.nanoTime();
        SelfCheckReport report = selfChecks.check();
        long checkMs = (System.nanoTime() - checkStarted) / 1_000_000;
        assertThat(report.failures()).as("%s", report.checks()).isEmpty();
        assertThat(checkMs).as("autoverificación de 300 estados en %d ms", checkMs).isLessThan(3_000);
    }

    // ── utilidades ───────────────────────────────────────────────────────────────────────────────

    private static ClosingCutoff cutoff(UUID run, UUID cuenta, UUID titular, String producto, String comportamiento,
                                        String titularTipo) {
        return new ClosingCutoff(UUID.randomUUID().toString(), run, cuenta, titular, producto, comportamiento,
                titularTipo, 3, Samples.DESDE, Samples.CORTE, Samples.LIMITE, new BigDecimal("16666.66"),
                new BigDecimal("16000"), new BigDecimal("666.66"),
                "REVOLVING".equals(comportamiento) ? new BigDecimal("16666.66") : null,
                "REVOLVING_LINE".equals(producto) ? new BigDecimal("833.33") : null, 0);
    }

    private void send(ClosingCutoff c) {
        kafka.send("closing.cutoff-closed", c.creditAccountId().toString(), c);
    }

    private int count(String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM statement.statements WHERE status = ?", Integer.class, status);
    }

    private int drain(int expected) {
        int n = 0;
        long hasta = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < hasta && n < expected) n += generated.poll(Duration.ofMillis(300)).count();
        return n;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void waitUntil(BooleanSupplier condition, int seconds) {
        long hasta = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < hasta) {
            if (condition.getAsBoolean()) return;
            sleep(200);
        }
        throw new AssertionError("No se cumplió en " + seconds + " s");
    }
}
