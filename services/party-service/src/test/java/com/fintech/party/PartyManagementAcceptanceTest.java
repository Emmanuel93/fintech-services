package com.fintech.party;

/**
 * Acceptance criteria — party-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /parties/{id} seeded            → 200
 * AC-2  GET /parties/{unknown}              → 404
 * AC-3  GET /parties/by-prospect/{id}       → 200
 * AC-4  PUT /parties/{id}/kyc-status        → 200 (adds a verification)
 * AC-5  GET /parties/{id}/kyc-verifications → 200 with the added verification
 * AC-6  POST /parties/{id}/blacklist        → 200 BLACKLISTED
 */

import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"origination.prospect-created", "party.party-blacklisted"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class PartyManagementAcceptanceTest {

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

    @Autowired TestRestTemplate restTemplate;
    @Autowired PartyRepository partyRepository;

    private Party seedParty(UUID prospectId) {
        String uniq = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String curp = uniq.substring(0, 18); // uq_parties_curp — must be unique per seed
        String rfc  = uniq.substring(0, 13);
        Party p = Party.create(UUID.randomUUID(), prospectId, PartyType.INDIVIDUAL,
                "Juan", "García", "López", curp, rfc, LocalDate.of(1990, 1, 1));
        return partyRepository.save(p);
    }

    @Test
    void ac1_getById_returns200() {
        Party p = seedParty(UUID.randomUUID());
        var resp = getMap("/api/v1/parties/" + p.getPartyId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("partyId")).isEqualTo(p.getPartyId().toString());
    }

    @Test
    void ac2_getById_unknown_returns404() {
        var resp = getMap("/api/v1/parties/" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac3_getByProspect_returns200() {
        UUID prospectId = UUID.randomUUID();
        seedParty(prospectId);
        var resp = getMap("/api/v1/parties/by-prospect/" + prospectId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("prospectId")).isEqualTo(prospectId.toString());
    }

    @Test
    void ac4_updateKycStatus_returns200() {
        Party p = seedParty(UUID.randomUUID());
        var resp = putJson("/api/v1/parties/" + p.getPartyId() + "/kyc-status",
                Map.of("documentType", "INE", "verificationStatus", "VERIFIED",
                        "verifiedBy", "kyc-officer", "documentRef", "DOC-1"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac5_listKycVerifications_returns200() {
        Party p = seedParty(UUID.randomUUID());
        putJson("/api/v1/parties/" + p.getPartyId() + "/kyc-status",
                Map.of("documentType", "INE", "verificationStatus", "VERIFIED",
                        "verifiedBy", "kyc-officer", "documentRef", "DOC-1"));

        var resp = getList("/api/v1/parties/" + p.getPartyId() + "/kyc-verifications");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void ac6_blacklist_returns200Blacklisted() {
        Party p = seedParty(UUID.randomUUID());
        var resp = postJson("/api/v1/parties/" + p.getPartyId() + "/blacklist",
                Map.of("reason", "OFAC match", "sourceList", "OFAC"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("BLACKLISTED");
    }

    @Test
    void ac7_updateFiscalProfile_returns200WithCfdiFields() {
        Party p = seedParty(UUID.randomUUID());
        var resp = putJson("/api/v1/parties/" + p.getPartyId() + "/fiscal-profile",
                Map.of("taxName", "JUAN GARCIA LOPEZ", "taxRegime", "612",
                        "taxZipCode", "06600", "cfdiUse", "G03"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("taxRegime")).isEqualTo("612");
        assertThat(resp.getBody().get("taxName")).isEqualTo("JUAN GARCIA LOPEZ");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getMap(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, h),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h),
                new ParameterizedTypeReference<>() {});
    }
}
