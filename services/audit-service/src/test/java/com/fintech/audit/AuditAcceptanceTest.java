package com.fintech.audit;

/**
 * Acceptance criteria — audit-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /entries no token                       → 401
 * AC-2  GET /entries wrong role (CUSTOMER)          → 403
 * AC-3  GET /entries?aggregateId= as AUDITOR        → 200 with the seeded entry
 * AC-4  GET /entries/{id} as AUDITOR                → 200
 * AC-5  GET /entries/{unknown}                      → 404
 * AC-6  GET /entries?eventType= as REGULATOR        → 200
 */

import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.domain.AuditEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "origination.prospect-created", "origination.score-requested",
        "origination.contract-signed", "origination.application-rejected",
        "scoring.scoring-approved", "scoring.scoring-completed",
        "configuration.configuration-updated",
        "product-catalog.product-activated", "product-catalog.product-retired",
        "charges.charge-applied"
})
class AuditAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired AuditService auditService;

    private AuditEntry seedEntry(String aggregateId, String partyId) {
        return auditService.record("ORIGINATION_PROSPECT_CREATED", "origination",
                aggregateId, partyId, UUID.randomUUID().toString(),
                "{\"prospectId\":\"" + aggregateId + "\"}");
    }

    @Test
    void ac1_noToken_returns401() {
        var resp = getRaw("/api/v1/audit/entries", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_wrongRole_returns403() {
        var resp = getRaw("/api/v1/audit/entries", headers("CUSTOMER"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ac3_listByAggregate_returns200WithEntry() {
        String aggregateId = UUID.randomUUID().toString();
        seedEntry(aggregateId, UUID.randomUUID().toString());

        var resp = getList("/api/v1/audit/entries?aggregateId=" + aggregateId, headers("AUDITOR"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anySatisfy(e ->
                assertThat(e.get("aggregateId")).isEqualTo(aggregateId));
    }

    @Test
    void ac4_getById_returns200() {
        AuditEntry entry = seedEntry(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        var resp = getMap("/api/v1/audit/entries/" + entry.getEntryId(), headers("AUDITOR"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("eventType")).isEqualTo("ORIGINATION_PROSPECT_CREATED");
    }

    @Test
    void ac5_getById_unknown_returns404() {
        var resp = getMap("/api/v1/audit/entries/" + UUID.randomUUID(), headers("AUDITOR"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac6_listByEventType_asRegulator_returns200() {
        seedEntry(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        var resp = getList("/api/v1/audit/entries?eventType=ORIGINATION_PROSPECT_CREATED", headers("REGULATOR"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<List<Map<String, Object>>> getList(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers != null ? headers : new HttpHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> getMap(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers != null ? headers : new HttpHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<String> getRaw(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers != null ? headers : new HttpHeaders()), String.class);
    }

    private HttpHeaders headers(String roles) {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", roles);
        return h;
    }
}
