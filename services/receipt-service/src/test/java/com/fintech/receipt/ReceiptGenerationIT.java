package com.fintech.receipt;

import com.fintech.receipt.application.port.out.ReceiptRepository;
import com.fintech.receipt.application.port.out.ReceiptSource;
import com.fintech.receipt.domain.Receipt;
import com.fintech.receipt.domain.ReceiptStatus;
import com.fintech.receipt.support.ReceiptSamples;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The receipts against Postgres and Kafka: a cutoff-closed message of a distributor's line becomes one receipt per
 * billed client, with its PDF; delivered twice it is still one; the API lists them, serves the PDF and prints them
 * again from their snapshot.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "fintech.receipt.retry.enabled=false",
        "fintech.receipt.partitions=3"
})
@EmbeddedKafka(partitions = 3, topics = {"closing.cutoff-closed", "receipt.receipt-generated"})
@AutoConfigureMockMvc
class ReceiptGenerationIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("receipt"); }

    @MockitoBean ReceiptSource source;
    @Autowired KafkaTemplate<String, Object> kafka;
    @Autowired ReceiptRepository receipts;
    @Autowired MockMvc mvc;

    record ClosingCutoff(String eventId, UUID runId, UUID creditAccountId, UUID obligorPartyId, String productType,
                         String productBehavior, String holderKind, int cycleNumber, LocalDate periodStart,
                         LocalDate cutoffDate, LocalDate paymentDueDate, BigDecimal balanceAtCutoff,
                         BigDecimal principalAtCutoff, BigDecimal interestAtCutoff, BigDecimal amountDue,
                         BigDecimal minimumPayment, int daysDelinquent) {}

    @Test
    @DisplayName("el corte de una línea de distribuidora da un recibo por cliente facturado, una sola vez, con su PDF")
    void aCutoffBecomesItsClientsReceipts() throws Exception {
        UUID line = UUID.randomUUID(), her = UUID.randomUUID(), ana = UUID.randomUUID(), beto = UUID.randomUUID(), carla = UUID.randomUUID();
        when(source.view(eq(line), any(), any(), any())).thenReturn(ReceiptSamples.view(line, her, ana, beto, carla));
        when(source.references(line)).thenReturn(Map.of(ana, 1234566L, beto, 1234574L));
        when(source.parties(anyCollection())).thenReturn(Map.of(
                ana, new ReceiptSource.PartySummary(ana, "Ana Torres", null),
                her, new ReceiptSource.PartySummary(her, "Laura Méndez Ortiz", null)));
        when(source.profiles(anyCollection())).thenReturn(Map.of(
                ana, new ReceiptSource.ClientProfile(ana, "NUEVO", List.of(), 2, 0, 2)));

        ClosingCutoff cutoff = new ClosingCutoff("evt-1", UUID.randomUUID(), line, her, "DISTRIBUTOR_LINE", "REVOLVING",
                "DISTRIBUTOR", 21, ReceiptSamples.FROM, ReceiptSamples.CUTOFF, ReceiptSamples.DUE,
                new BigDecimal("15200.00"), new BigDecimal("14000.00"), new BigDecimal("1200.00"), new BigDecimal("2600.00"),
                null, 0);
        kafka.send("closing.cutoff-closed", line.toString(), cutoff).get(10, TimeUnit.SECONDS);
        // A product that pays no distributor: same topic, no receipts.
        kafka.send("closing.cutoff-closed", UUID.randomUUID().toString(), new ClosingCutoff("evt-2", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 3, ReceiptSamples.FROM,
                ReceiptSamples.CUTOFF, ReceiptSamples.DUE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN,
                null, 0)).get(10, TimeUnit.SECONDS);

        waitUntil(() -> generated(line).size() == 2, 60);
        List<Receipt> got = generated(line);
        assertThat(got).extracting(Receipt::beneficiaryPartyId).containsExactlyInAnyOrder(ana, beto);
        assertThat(got).allSatisfy(r -> {
            assertThat(r.clabe()).isEqualTo("646180157000000004");
            assertThat(r.templateVersion()).startsWith("recibo-pago-distribuidora@");
            assertThat(r.documentBytes()).isPositive();
            assertThat(r.distributorPartyId()).isEqualTo(her);
        });
        assertThat(got.stream().map(Receipt::totalDue).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("2092.95");

        // Delivered again: the natural key keeps it one receipt per client.
        kafka.send("closing.cutoff-closed", line.toString(), cutoff).get(10, TimeUnit.SECONDS);
        Thread.sleep(1500);
        assertThat(receipts.search(line, null, null, 50)).hasSize(2);

        Receipt anas = got.stream().filter(r -> r.beneficiaryPartyId().equals(ana)).findFirst().orElseThrow();
        mvc.perform(get("/api/v1/receipts").param("partyId", her.toString()).header("X-User-Id", "bo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/receipts").param("partyId", ana.toString()).header("X-User-Id", "bo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].folio").value("RC-20251021-1234566"))
                .andExpect(jsonPath("$[0].clientType").value("NUEVO"));
        mvc.perform(get("/api/v1/receipts/{id}/pdf", anas.receiptId()).header("X-User-Id", "bo-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mvc.perform(get("/api/v1/receipts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/receipts").header("X-User-Id", "bo-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/receipts/regenerate").header("X-User-Id", "bo-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"creditAccountId\":\"" + line + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regenerated").value(2))
                .andExpect(jsonPath("$.failed").value(0));
    }

    private List<Receipt> generated(UUID line) {
        return receipts.search(line, null, null, 50).stream().filter(r -> r.status() == ReceiptStatus.GENERATED).toList();
    }

    private static void waitUntil(BooleanSupplier condition, int seconds) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(200);
        }
        throw new AssertionError("No se cumplió en " + seconds + " s");
    }
}
