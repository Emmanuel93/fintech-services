package com.fintech.creditproduct;

import com.fintech.creditproduct.domain.*;
import com.fintech.creditproduct.infrastructure.adapter.in.api.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance / Integration tests for credit-product-service.
 *
 * Infrastructure:
 *   - Testcontainers PostgreSQL 16 (fresh DB, all Liquibase migrations run)
 *   - EmbeddedKafka (brokers injected via bootstrapServersProperty)
 *
 * Security model:
 *   - GET /api/v1/credit-products/** → público (no requiere identidad)
 *   - POST / PUT → requieren identidad de empleado
 *
 * <p>La identidad llega en {@code X-User-Id}/{@code X-Roles}, igual que a los demás servicios de
 * dominio: el gateway valida el token y reescribe esos headers. La prueba llama <b>como llama el
 * BFF</b> — antes minteaba un JWT con un secreto que ella misma inyectaba, y por eso no veía que en
 * el despliegue ese secreto no existía y toda escritura del catálogo respondía 401.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                "product-catalog.product-activated",
                "product-catalog.product-retired"
        })
class CreditProductCatalogIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech")
            .withUsername("fintech")
            .withPassword("fintech");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    // ── Identidad de empleado, tal como la propaga el BFF ────────────────────

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "ADMIN");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private <T> HttpEntity<T> auth(T body) {
        return new HttpEntity<>(body, authHeaders());
    }

    private HttpEntity<Void> auth() {
        return new HttpEntity<>(authHeaders());
    }

    // ── Seed verification (GET — public, no token needed) ────────────────────

    @Test
    void seedProducts_areActiveOnStartup() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products"), CreditProductDefinitionResponse[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 5 B2C + 2 B2C (CC+ML) + 2 B2B = 9 ACTIVE products
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    void seedProducts_containAllExpectedCodes() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products"), CreditProductDefinitionResponse[].class);
        Set<String> codes = Arrays.stream(resp.getBody())
                .map(CreditProductDefinitionResponse::productCode)
                .collect(Collectors.toSet());
        assertThat(codes).contains(
                "PL-IND-STD-V1", "RL-IND-STD-V1", "PY-IND-STD-V1",
                "DL-DIST-STD-V1", "GL-IND-STD-V1",
                "CC-IND-STD-V1", "ML-IND-STD-V1",
                "SME-LOAN-STD-V1", "BRL-BUS-STD-V1"
        );
    }

    @Test
    void seedProducts_allHaveCapabilities() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products"), CreditProductDefinitionResponse[].class);
        assertThat(resp.getBody()).allMatch(p -> p.capabilities() != null);
    }

    @Test
    void seedProducts_allHaveProductVersion1() {
        // Filter to the known seed codes — other test methods may create extra versions
        // in the shared Testcontainers DB (tests are not DB-isolated under TestRestTemplate).
        Set<String> seedCodes = Set.of(
                "PL-IND-STD-V1", "RL-IND-STD-V1", "PY-IND-STD-V1", "DL-DIST-STD-V1",
                "GL-IND-STD-V1", "CC-IND-STD-V1", "ML-IND-STD-V1",
                "SME-LOAN-STD-V1", "BRL-BUS-STD-V1");
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products"), CreditProductDefinitionResponse[].class);
        assertThat(resp.getBody())
                .filteredOn(p -> seedCodes.contains(p.productCode()))
                .allMatch(p -> p.productVersion() == 1);
    }

    // ── Catálogo completo para administración (GET /all — cualquier estado) ──

    @Test
    void findAll_returnsSupersetOfActive_forCatalogAdmin() {
        ResponseEntity<CreditProductDefinitionResponse[]> all = rest.getForEntity(
                url("/api/v1/credit-products/all"), CreditProductDefinitionResponse[].class);
        ResponseEntity<CreditProductDefinitionResponse[]> active = rest.getForEntity(
                url("/api/v1/credit-products"), CreditProductDefinitionResponse[].class);

        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        // /all (cualquier estado) es superconjunto de los activos y trae los seeds.
        assertThat(all.getBody().length).isGreaterThanOrEqualTo(active.getBody().length);
        Set<String> codes = Arrays.stream(all.getBody())
                .map(CreditProductDefinitionResponse::productCode)
                .collect(Collectors.toSet());
        assertThat(codes).contains("PL-IND-STD-V1", "BRL-BUS-STD-V1");
    }

    // ── Audience filtering (GET — public) ────────────────────────────────────

    @Test
    void filterByAudience_b2b_returnsSmeAndBusinessRevolving() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products?targetAudience=B2B"), CreditProductDefinitionResponse[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
        assertThat(resp.getBody()).allMatch(p -> "B2B".equals(p.targetAudience()));
        Set<String> codes = Arrays.stream(resp.getBody())
                .map(CreditProductDefinitionResponse::productCode)
                .collect(Collectors.toSet());
        assertThat(codes).contains("SME-LOAN-STD-V1", "BRL-BUS-STD-V1");
    }

    @Test
    void filterByAudience_b2b2c_onlyDistributorLine() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products?targetAudience=B2B2C"), CreditProductDefinitionResponse[].class);
        assertThat(resp.getBody()).allMatch(p -> "B2B2C".equals(p.targetAudience()));
        assertThat(resp.getBody()).anyMatch(p -> "DL-DIST-STD-V1".equals(p.productCode()));
    }

    @Test
    void filterByProductType_smeLoan_b2bInstallment() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products?productType=SME_LOAN"), CreditProductDefinitionResponse[].class);
        assertThat(resp.getBody()).allMatch(p -> "SME_LOAN".equals(p.productType()));
        assertThat(resp.getBody()).allMatch(p -> "INSTALLMENT".equals(p.behavior()));
        assertThat(resp.getBody()).allMatch(p -> "B2B".equals(p.targetAudience()));
    }

    // ── Capabilities content (GET — public) ──────────────────────────────────

    @Test
    void capabilities_personalLoan_installmentSelfUse() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/PL-IND-STD-V1"), CreditProductDefinitionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.hasAmortizationSchedule()).isTrue();
        assertThat(c.hasCreditLimit()).isFalse();
        assertThat(c.dispositionType()).isEqualTo("SELF_USE");
        assertThat(c.commissionsEnabled()).isFalse();
    }

    @Test
    void capabilities_distributorLine_thirdPartyWithCommissions() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/DL-DIST-STD-V1"), CreditProductDefinitionResponse.class);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.dispositionType()).isEqualTo("THIRD_PARTY_CREDIT");
        assertThat(c.commissionsEnabled()).isTrue();
        assertThat(c.requiresBeneficiaryPartyId()).isTrue();
    }

    @Test
    void capabilities_payrollLoan_payrollDisposition() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/PY-IND-STD-V1"), CreditProductDefinitionResponse.class);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.dispositionType()).isEqualTo("PAYROLL");
        assertThat(c.hasAmortizationSchedule()).isTrue();
        assertThat(c.hasCreditLimit()).isFalse();
    }

    @Test
    void capabilities_groupLoan_multipleObligors() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/GL-IND-STD-V1"), CreditProductDefinitionResponse.class);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.allowsMultipleObligors()).isTrue();
        assertThat(c.hasAmortizationSchedule()).isTrue();
    }

    @Test
    void capabilities_creditCard_revolvingWithCutoff() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/CC-IND-STD-V1"), CreditProductDefinitionResponse.class);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.hasCreditLimit()).isTrue();
        assertThat(c.hasCutoffDate()).isTrue();
        assertThat(c.hasMinimumPayment()).isTrue();
        assertThat(c.hasAmortizationSchedule()).isFalse();
    }

    @Test
    void capabilities_smeLoan_noRevolvingFeatures() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/SME-LOAN-STD-V1"), CreditProductDefinitionResponse.class);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.hasAmortizationSchedule()).isTrue();
        assertThat(c.hasCreditLimit()).isFalse();
        assertThat(c.commissionsEnabled()).isFalse();
    }

    @Test
    void capabilities_businessRevolvingLine_noCommissionsNoBeneficiary() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/BRL-BUS-STD-V1"), CreditProductDefinitionResponse.class);
        Capabilities c = resp.getBody().capabilities();
        assertThat(c.hasCreditLimit()).isTrue();
        assertThat(c.allowsMultipleDispositions()).isTrue();
        assertThat(c.commissionsEnabled()).isFalse();
        assertThat(c.requiresBeneficiaryPartyId()).isFalse();
    }

    // ── Rate cards (GET — public) ─────────────────────────────────────────────

    @Test
    void rateCards_personalLoan_hasB2cTierBands() {
        UUID id = getIdByCode("PL-IND-STD-V1");
        ResponseEntity<RateCardResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/rate-cards"), RateCardResponse[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(3);
        Set<String> tiers = Arrays.stream(resp.getBody())
                .map(RateCardResponse::tierBand)
                .collect(Collectors.toSet());
        assertThat(tiers).contains("T1", "T2", "T3");
        // T1 has the best rate
        BigDecimal t1Rate = Arrays.stream(resp.getBody())
                .filter(rc -> "T1".equals(rc.tierBand())).findFirst().orElseThrow().nominalRate();
        BigDecimal t3Rate = Arrays.stream(resp.getBody())
                .filter(rc -> "T3".equals(rc.tierBand())).findFirst().orElseThrow().nominalRate();
        assertThat(t1Rate).isLessThan(t3Rate);
    }

    @Test
    void rateCards_smeLoan_hasAmountBands() {
        UUID id = getIdByCode("SME-LOAN-STD-V1");
        ResponseEntity<RateCardResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/rate-cards"), RateCardResponse[].class);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(3);
        // smallest tranche pays most
        BigDecimal smallRate = Arrays.stream(resp.getBody())
                .filter(rc -> rc.minAmount() != null
                        && rc.minAmount().compareTo(new BigDecimal("50000")) == 0)
                .findFirst().orElseThrow().nominalRate();
        BigDecimal largeRate = Arrays.stream(resp.getBody())
                .filter(rc -> rc.minAmount() != null
                        && rc.minAmount().compareTo(new BigDecimal("2000000.01")) == 0)
                .findFirst().orElseThrow().nominalRate();
        assertThat(smallRate).isGreaterThan(largeRate);
    }

    @Test
    void rateCards_businessRevolvingLine_hasTierBands() {
        UUID id = getIdByCode("BRL-BUS-STD-V1");
        ResponseEntity<RateCardResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/rate-cards"), RateCardResponse[].class);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void rateCards_distributorLine_hasAmountBands() {
        UUID id = getIdByCode("DL-DIST-STD-V1");
        ResponseEntity<RateCardResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/rate-cards"), RateCardResponse[].class);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(2);
    }

    // ── Eligibility rules (GET — public) ─────────────────────────────────────

    @Test
    void eligibilityRules_smeLoan_requiresBusinessPartyType() {
        UUID id = getIdByCode("SME-LOAN-STD-V1");
        ResponseEntity<EligibilityRuleResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/eligibility-rules"),
                EligibilityRuleResponse[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(2);
        boolean hasPartyType = Arrays.stream(resp.getBody())
                .anyMatch(r -> "REQUIRED_PARTY_TYPE".equals(r.ruleType())
                        && "BUSINESS".equals(r.stringValue()));
        assertThat(hasPartyType).as("SME_LOAN must require BUSINESS party type").isTrue();
    }

    @Test
    void eligibilityRules_businessRevolvingLine_hasDtiAndScore() {
        UUID id = getIdByCode("BRL-BUS-STD-V1");
        ResponseEntity<EligibilityRuleResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/eligibility-rules"),
                EligibilityRuleResponse[].class);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(3);
        boolean hasDti = Arrays.stream(resp.getBody())
                .anyMatch(r -> "MAX_DEBT_TO_INCOME_RATIO".equals(r.ruleType()));
        boolean hasScore = Arrays.stream(resp.getBody())
                .anyMatch(r -> "MIN_SCORE".equals(r.ruleType()));
        assertThat(hasDti).isTrue();
        assertThat(hasScore).isTrue();
    }

    // ── Versioning (GET public; POST/PUT require auth) ────────────────────────

    @Test
    void versioning_historyEndpoint_returnsAllVersions() {
        ResponseEntity<CreditProductDefinitionResponse[]> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/PL-IND-STD-V1/versions"),
                CreditProductDefinitionResponse[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void versioning_codeEndpoint_returnsActiveVersion() {
        ResponseEntity<CreditProductDefinitionResponse> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/PL-IND-STD-V1"), CreditProductDefinitionResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().status()).isEqualTo("ACTIVE");
    }

    @Test
    void versioning_createAndActivate_sameCode_retiresOldVersion() {
        // V1 already exists in seeds (VERS-CODE doesn't → version 1)
        CreateCreditProductRequest v1Req = personalLoanRequest("VERS-RETIRE-TEST");
        ResponseEntity<CreditProductDefinitionResponse> v1 = rest.exchange(
                url("/api/v1/credit-products"), HttpMethod.POST,
                auth(v1Req), CreditProductDefinitionResponse.class);
        assertThat(v1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(v1.getBody().productVersion()).isEqualTo(1);
        UUID v1Id = v1.getBody().productDefinitionId();

        // Activate V1
        rest.exchange(url("/api/v1/credit-products/" + v1Id + "/activate"),
                HttpMethod.PUT, auth(), CreditProductDefinitionResponse.class);

        // Create V2 same code
        ResponseEntity<CreditProductDefinitionResponse> v2 = rest.exchange(
                url("/api/v1/credit-products"), HttpMethod.POST,
                auth(personalLoanRequest("VERS-RETIRE-TEST")), CreditProductDefinitionResponse.class);
        assertThat(v2.getBody().productVersion()).isEqualTo(2);
        UUID v2Id = v2.getBody().productDefinitionId();

        // Activate V2 — V1 must become RETIRED
        ResponseEntity<CreditProductDefinitionResponse> activated = rest.exchange(
                url("/api/v1/credit-products/" + v2Id + "/activate"),
                HttpMethod.PUT, auth(), CreditProductDefinitionResponse.class);
        assertThat(activated.getBody().status()).isEqualTo("ACTIVE");

        // V1 should now be RETIRED
        ResponseEntity<CreditProductDefinitionResponse> v1Check = rest.getForEntity(
                url("/api/v1/credit-products/" + v1Id), CreditProductDefinitionResponse.class);
        assertThat(v1Check.getBody().status()).isEqualTo("RETIRED");
        assertThat(v1Check.getBody().retiredAt()).isNotNull();
    }

    // ── Create with rate cards + eligibility rules (POST — requires auth) ────

    @Test
    void create_withRateCardsAndEligibilityRules_persistsAll() {
        CreateCreditProductRequest req = new CreateCreditProductRequest(
                "SME-IT-TIERED-V1", "SME_LOAN", "SME Tiered IT", null,
                "B2B", "MXN",
                new BigDecimal("0.24"), new BigDecimal("0.40"),
                6, 48, 24,
                new BigDecimal("50000"), new BigDecimal("5000000"),
                null, null, null,
                5000, null,
                "FRENCH", "MONTHLY", Set.of("MONTHLY"),
                300, "COMMITTEE",
                new BigDecimal("0.015"), new BigDecimal("0.03"),
                Set.of("BUSINESS"),
                Set.of(new RequiredDocumentRequest("TAX_RETURN", true)),
                Set.of("BRANCH"),
                null,
                List.of(
                        new RateCardRequest(null,
                                new BigDecimal("50000"), new BigDecimal("500000"), null, null,
                                new BigDecimal("0.2600"), new BigDecimal("0.4200")),
                        new RateCardRequest(null,
                                new BigDecimal("500000.01"), new BigDecimal("5000000"), null, null,
                                new BigDecimal("0.2000"), new BigDecimal("0.3800"))
                ),
                List.of(
                        new EligibilityRuleRequest("REQUIRED_PARTY_TYPE", null, null, "BUSINESS",
                                "ELIG_IT_PARTY"),
                        new EligibilityRuleRequest("MAX_DEBT_TO_INCOME_RATIO", "LTE",
                                new BigDecimal("0.50"), null, "ELIG_IT_DTI")
                )
        );

        ResponseEntity<CreditProductDefinitionResponse> created = rest.exchange(
                url("/api/v1/credit-products"), HttpMethod.POST,
                auth(req), CreditProductDefinitionResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().productVersion()).isEqualTo(1);
        assertThat(created.getBody().status()).isEqualTo("DRAFT");
        assertThat(created.getBody().capabilities()).isNotNull();
        assertThat(created.getBody().capabilities().hasAmortizationSchedule()).isTrue();
        UUID id = created.getBody().productDefinitionId();

        ResponseEntity<RateCardResponse[]> rcResp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/rate-cards"), RateCardResponse[].class);
        assertThat(rcResp.getBody()).hasSize(2);

        ResponseEntity<EligibilityRuleResponse[]> erResp = rest.getForEntity(
                url("/api/v1/credit-products/" + id + "/eligibility-rules"),
                EligibilityRuleResponse[].class);
        assertThat(erResp.getBody()).hasSize(2);
        assertThat(erResp.getBody()).anyMatch(r -> "REQUIRED_PARTY_TYPE".equals(r.ruleType())
                && "BUSINESS".equals(r.stringValue()));
    }

    @Test
    void create_withCustomCapabilities_overridesDefault() {
        Capabilities customCaps = new Capabilities(
                true, false, false, "SELF_USE", false, false, false, false, false);

        CreateCreditProductRequest req = new CreateCreditProductRequest(
                "PL-CUSTOM-CAPS-IT", "PERSONAL_LOAN", "Custom Caps IT", null,
                "B2C", "MXN",
                new BigDecimal("0.30"), new BigDecimal("0.50"),
                3, 24, 12,
                new BigDecimal("5000"), new BigDecimal("100000"),
                null, null, null,
                1000, null,
                "FRENCH", "MONTHLY", Set.of("MONTHLY"),
                200, "AUTOMATIC",
                new BigDecimal("0.01"), BigDecimal.ZERO,
                Set.of("INDIVIDUAL"),
                Set.of(new RequiredDocumentRequest("INCOME_PROOF", true)),
                Set.of("WEB"),
                customCaps, null, null
        );

        ResponseEntity<CreditProductDefinitionResponse> resp = rest.exchange(
                url("/api/v1/credit-products"), HttpMethod.POST,
                auth(req), CreditProductDefinitionResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().capabilities().dispositionType()).isEqualTo("SELF_USE");
    }

    // ── Security — mutations return 401 without token ─────────────────────────

    @Test
    void create_withoutToken_returns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                url("/api/v1/credit-products"),
                personalLoanRequest("SEC-TEST"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void activate_withoutToken_returns401() {
        ResponseEntity<String> resp = rest.exchange(
                url("/api/v1/credit-products/" + UUID.randomUUID() + "/activate"),
                HttpMethod.PUT, null, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    void findById_notFound_returns404() {
        ResponseEntity<String> resp = rest.getForEntity(
                url("/api/v1/credit-products/" + UUID.randomUUID()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void findByCode_notFound_returns404() {
        ResponseEntity<String> resp = rest.getForEntity(
                url("/api/v1/credit-products/code/NONEXISTENT"), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private UUID getIdByCode(String code) {
        return rest.getForObject(
                url("/api/v1/credit-products/code/" + code),
                CreditProductDefinitionResponse.class).productDefinitionId();
    }

    private CreateCreditProductRequest personalLoanRequest(String code) {
        return new CreateCreditProductRequest(
                code, "PERSONAL_LOAN", "Versioning Test", null, "B2C", "MXN",
                new BigDecimal("0.32"), new BigDecimal("0.55"),
                3, 36, 12,
                new BigDecimal("5000"), new BigDecimal("150000"),
                null, null, null,
                1000, null,
                "FRENCH", "MONTHLY", Set.of("MONTHLY"),
                200, "AUTOMATIC",
                new BigDecimal("0.01"), new BigDecimal("0.02"),
                Set.of("INDIVIDUAL"),
                Set.of(new RequiredDocumentRequest("INCOME_PROOF", true)),
                Set.of("WEB"),
                null, null, null
        );
    }
}
