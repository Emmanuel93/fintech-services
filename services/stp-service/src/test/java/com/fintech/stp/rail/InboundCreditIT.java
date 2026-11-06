package com.fintech.stp.rail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.port.in.IssueCollectionAccountUseCase;
import com.fintech.stp.application.port.in.ManageCollectionAccountUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.collection.CollectionAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Collection by CLABE against a real Postgres: a person's CLABE, credits received on it through the webhook STP
 * calls and through the simulator the E2E uses, the decision answered while STP waits, the two dates of the money,
 * the idempotency of STP's retries, and the announcement left in the outbox in the same transaction.
 */
@SpringBootTest(properties = "fintech.test-support.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InboundCreditIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("stp"); }
    @Override protected List<String> preserve() { return List.of("business_calendar_days"); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StpCompanyRepository companies;
    @Autowired IssueCollectionAccountUseCase issue;
    @Autowired ManageCollectionAccountUseCase manage;
    @Autowired BusinessClock clock;

    private UUID party;

    @BeforeEach
    void company() {
        companies.save(StpCompany.create("KREDIUS", "KREDIUS", 90646, "KRD", "646", "180", "3780"));
        party = UUID.randomUUID();
    }

    @AfterEach
    void realClock() {
        clock.resetShift();
    }

    @Test
    @DisplayName("una persona recibe una sola CLABE de cobro, la primera del rango, y se anuncia por el outbox")
    void onePersonOneClabe() {
        CollectionAccount first = issue.issueFor(party, "ANA PEREZ", "PEPA800101ABC");
        CollectionAccount again = issue.issueFor(party, "ANA PEREZ", "PEPA800101ABC");

        assertThat(first.getClabe()).isEqualTo("646180378000000016");
        assertThat(again.getCollectionAccountId()).isEqualTo(first.getCollectionAccountId());
        assertThat(issue.issueFor(UUID.randomUUID(), null, null).getClabe()).isEqualTo("646180378000000029");
        assertThat(outboxTopics()).containsExactly("stp.collection-account-assigned", "stp.collection-account-assigned");
    }

    @Test
    @DisplayName("un abono del 30-nov a las 19:00 se confirma: cartera 30-nov, banco 1-dic, y sale a stp.credit-received")
    void creditAfterCutoffKeepsBothDates() throws Exception {
        String clabe = issue.issueFor(party, null, null).getClabe();
        clock.shiftTo(LocalDateTime.of(2026, 11, 30, 19, 0));

        MvcResult result = mvc.perform(post("/internal/test-support/spei/abonos").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("clabe", clabe, "amount", 5000, "numericReference", 1234567))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("CONFIRMED"))
                .andExpect(jsonPath("$.operationDate").value("2026-12-01"))
                .andReturn();
        String trackingKey = json.readTree(result.getResponse().getContentAsString()).get("trackingKey").asText();
        assertThat(trackingKey).isEqualTo("SIMSPEI20261201000001");

        JsonNode event = lastEvent("stp.credit-received");
        assertThat(event.get("decision").asText()).isEqualTo("CONFIRMED");
        assertThat(event.get("operationDate").asText()).isEqualTo("2026-12-01");
        assertThat(event.get("receivedAt").asText()).startsWith("2026-12-01T01:00");   // 19:00 CDMX = 01:00Z
        assertThat(event.get("partyId").asText()).isEqualTo(party.toString());
        assertThat(event.get("numericReference").asLong()).isEqualTo(1234567L);
        assertThat(event.get("amount").decimalValue()).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("STP reintenta el webhook: el mismo abono recibe la misma respuesta y no se registra dos veces")
    void retriesAreIdempotent() throws Exception {
        String clabe = issue.issueFor(party, null, null).getClabe();
        String body = json.writeValueAsString(Map.of("clabe", clabe, "amount", 800, "trackingKey", "BANCO123", "stpId", 99L));

        mvc.perform(post("/internal/test-support/spei/abonos").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/internal/test-support/spei/abonos").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.decision").value("CONFIRMED"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stp.inbound_credits", Integer.class)).isEqualTo(1);
        assertThat(outboxTopics()).filteredOn("stp.credit-received"::equals).hasSize(1);
    }

    @Test
    @DisplayName("el webhook de STP: confirmar con 200; devolver con 400 y la causa de Banxico (1, 2, 15)")
    void webhookAnswersWithTheStpContract() throws Exception {
        String clabe = issue.issueFor(party, null, null).getClabe();

        webhook(stp(1L, "R1", clabe, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.mensaje").value("confirmar"));
        webhook(stp(2L, "R2", "646180378000009999", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("devolver")).andExpect(jsonPath("$.id").value(1));
        webhook(stp(3L, "R3", clabe, 7)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.id").value(15));
        manage.block(party, "aclaración en curso");
        webhook(stp(4L, "R4", clabe, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.id").value(2));

        // Returned credits stay in the inbox and are announced too: they explain the return in the bank statement.
        assertThat(jdbcTemplate.queryForList("SELECT decision FROM stp.inbound_credits ORDER BY stp_id", String.class))
                .containsExactly("CONFIRMED", "RETURNED", "RETURNED", "RETURNED");
        assertThat(manage.creditsOf(party)).hasSize(3);
    }

    @Test
    @DisplayName("recibir abonos no se vuelve lento: 200 abonos en menos de 15 segundos")
    void performance() throws Exception {
        String clabe = issue.issueFor(party, null, null).getClabe();
        long began = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            mvc.perform(post("/internal/test-support/spei/abonos").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("clabe", clabe, "amount", 100 + i)))).andExpect(status().isOk());
        }
        long ms = (System.nanoTime() - began) / 1_000_000;
        assertThat(ms).as("200 abonos en %d ms", ms).isLessThan(15_000);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stp.inbound_credits", Integer.class)).isEqualTo(200);
    }

    private ResultActions webhook(Map<String, Object> notification) throws Exception {
        return mvc.perform(post("/api/v1/stp/abonos").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(notification)));
    }

    private static Map<String, Object> stp(long id, String trackingKey, String clabe, int paymentType) {
        return Map.of("id", id, "fechaOperacion", 20261130, "claveRastreo", trackingKey, "monto", 1500.50,
                "cuentaBeneficiario", clabe, "nombreOrdenante", "ANA PEREZ", "cuentaOrdenante", "012180001234567891",
                "institucionOrdenante", 40012, "empresa", "KREDIUS", "tipoPago", paymentType);
    }

    private List<String> outboxTopics() {
        return jdbcTemplate.queryForList("SELECT payload::json->>'topic' FROM stp.outbox_messages "
                + "WHERE message_type = 'PUBLISH_EVENT' ORDER BY created_at", String.class);
    }

    private JsonNode lastEvent(String topic) throws Exception {
        String payload = jdbcTemplate.queryForObject("SELECT payload FROM stp.outbox_messages "
                + "WHERE message_type = 'PUBLISH_EVENT' AND payload::json->>'topic' = ? ORDER BY created_at DESC LIMIT 1",
                String.class, topic);
        return json.readTree(payload).get("body");
    }
}
