package com.fintech.collections;

/**
 * Acceptance criteria — collections-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /cases/{caseId} for a seeded case            → 200 with case body
 * AC-2  GET /accounts/{creditAccountId}/case             → 200 (active case lookup)
 * AC-3  POST /cases/{caseId}/payment-promises            → 201 PENDING/ACTIVE
 * AC-4  POST /cases/{caseId}/contact-attempts            → 201
 * AC-5  agreement lifecycle: propose → accept → authorize → EXECUTED
 * AC-6  write-off lifecycle: request (202) → approve (201)
 * AC-7  GET /bureau-reports (a write-off leaves a PENDING report)
 * AC-8  No token                                          → 401
 * AC-9  Unknown caseId                                    → 404
 */

import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.domain.AccountBalanceSnapshot;
import com.fintech.collections.domain.CollectionCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.delinquency-status-updated",
        "credit-portfolio.credit-account-activated",
        "credit-portfolio.balance-updated",
        "credit-portfolio.installment-upcoming",
        "payments.payment-applied",
        "collections.case-created",
        "collections.case-escalated",
        "collections.payment-promise-made",
        "collections.contact-attempt-registered",
        "collections.agreement-proposed",
        "collections.agreement-executed",
        "collections.write-off-requested",
        "collections.write-off-executed",
        "collections.bureau-report-submitted"
})
class CollectionsAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired CollectionCaseRepository caseRepository;
    @Autowired AccountBalanceSnapshotRepository snapshotRepository;

    /** Seeds a MANAGED case (agreements need MANAGED/LEGAL) plus its balance snapshot. */
    private CollectionCase seedManagedCase(int daysDelinquent, BigDecimal debt) {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();
        AccountBalanceSnapshot snap = AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        snap.upsert(debt, BigDecimal.ZERO, BigDecimal.ZERO, debt, 1L);
        snapshotRepository.save(snap);

        CollectionCase c = CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                daysDelinquent, debt, "AGENT_ASSIGNED_RESTRUCTURE_OFFER");
        c.escalate(daysDelinquent, debt, "AGENT_ASSIGNED_RESTRUCTURE_OFFER"); // OPEN -> MANAGED
        return caseRepository.save(c);
    }

    @Test
    void ac8_noToken_returns401() {
        var resp = getJson("/api/v1/collections/cases/" + UUID.randomUUID(), null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac9_unknownCase_returns404() {
        var resp = getJson("/api/v1/collections/cases/" + UUID.randomUUID(), userId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac1_getCase_returns200() {
        CollectionCase c = seedManagedCase(45, new BigDecimal("10000"));

        var resp = getJson("/api/v1/collections/cases/" + c.getCaseId(), userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("MANAGED");
        assertThat(resp.getBody().get("currentBucket")).isEqualTo("B31_60");
    }

    @Test
    void ac2_getActiveCaseByAccount_returns200() {
        CollectionCase c = seedManagedCase(45, new BigDecimal("10000"));

        var resp = getJson("/api/v1/collections/accounts/" + c.getCreditAccountId() + "/case", userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("caseId")).isEqualTo(c.getCaseId().toString());
    }

    @Test
    void ac3_createPaymentPromise_returns201() {
        CollectionCase c = seedManagedCase(45, new BigDecimal("10000"));

        var resp = postJson("/api/v1/collections/cases/" + c.getCaseId() + "/payment-promises",
                Map.of("amount", 1500, "promisedDate", LocalDate.now().plusDays(5).toString()),
                userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void ac4_recordContactAttempt_returns201() {
        CollectionCase c = seedManagedCase(45, new BigDecimal("10000"));

        var resp = postJson("/api/v1/collections/cases/" + c.getCaseId() + "/contact-attempts",
                Map.of("channel", "PHONE", "result", "NO_ANSWER"),
                userId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void ac5_agreementLifecycle_proposeAcceptAuthorize_executes() {
        CollectionCase c = seedManagedCase(45, new BigDecimal("10000"));
        var headers = userId();

        var propose = postJson("/api/v1/collections/cases/" + c.getCaseId() + "/agreements",
                Map.of("type", "QUITA_PARCIAL", "forgivenAmount", 2000), headers);
        assertThat(propose.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String agreementId = (String) propose.getBody().get("agreementId");

        var accept = putJson("/api/v1/collections/agreements/" + agreementId + "/accept", null, headers);
        assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept.getBody().get("status")).isEqualTo("ACCEPTED");

        var authorize = putJson("/api/v1/collections/agreements/" + agreementId + "/authorize",
                Map.of("authorizedBy", "ops-1", "authorizationRef", "AUTH-123"), headers);
        assertThat(authorize.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorize.getBody().get("status")).isEqualTo("EXECUTED");
    }

    @Test
    void ac6_writeOffLifecycle_requestThenApprove() {
        CollectionCase c = seedManagedCase(200, new BigDecimal("5000"));
        var headers = userId();

        var request = postJson("/api/v1/collections/cases/" + c.getCaseId() + "/request-write-off",
                Map.of("reason", "UNRECOVERABLE"), headers);
        assertThat(request.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        var approve = postJson("/api/v1/collections/cases/" + c.getCaseId() + "/write-offs",
                Map.of("reason", "UNRECOVERABLE", "authorizedBy", "ops-1", "authorizationRef", "WO-AUTH-1"),
                headers);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(approve.getBody().get("totalWrittenOff")).isNotNull();
    }

    @Test
    void ac7_bureauReports_listedAfterWriteOff() {
        CollectionCase c = seedManagedCase(200, new BigDecimal("5000"));
        var headers = userId();
        postJson("/api/v1/collections/cases/" + c.getCaseId() + "/write-offs",
                Map.of("reason", "UNRECOVERABLE", "authorizedBy", "ops-1", "authorizationRef", "WO-AUTH-2"),
                headers);

        var resp = getList("/api/v1/collections/bureau-reports", headers);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anySatisfy(r ->
                assertThat(r.get("creditAccountId")).isEqualTo(c.getCreditAccountId().toString()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> getJson(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers != null ? headers : new HttpHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path, HttpHeaders headers) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers != null ? headers : new HttpHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, h), new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, Object body, HttpHeaders headers) {
        HttpHeaders h = headers != null ? headers : new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PUT,
                new HttpEntity<>(body, h), new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders userId() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "COLLECTIONS_AGENT");
        return h;
    }
}
