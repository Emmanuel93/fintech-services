package com.fintech.scoring;

/**
 * Acceptance criteria — scoring-service (HTTP end-to-end, real Postgres + Kafka).
 * Complements ScoringFlowIT (event flow) with the read-side HTTP contract.
 *
 * AC-1  GET /scoring/policies                         → 200, seeded policy present
 * AC-2  GET /scoring/policies/{unknown}               → 404
 * AC-3  GET /scoring/evaluations/{unknown}/latest     → 404
 */

import com.fintech.scoring.application.port.out.CirculoGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "origination.prospect-created", "origination.score-requested",
        "scoring.scoring-completed", "scoring.scoring-approved"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class ScoringAcceptanceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean CirculoGateway circuloGateway;
    @Autowired TestRestTemplate restTemplate;

    @Test
    void ac1_listPolicies_returns200WithSeededPolicy() {
        var resp = restTemplate.exchange("/api/v1/scoring/policies", HttpMethod.GET,
                HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("policyId");
    }

    @Test
    void ac2_getPolicy_unknown_returns404() {
        var resp = restTemplate.exchange("/api/v1/scoring/policies/" + UUID.randomUUID(),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac3_getLatestEvaluation_unknown_returns404() {
        var resp = restTemplate.exchange("/api/v1/scoring/evaluations/" + UUID.randomUUID() + "/latest",
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
