package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase G IT — CreditProductCreationRequested → CreditAccount ACTIVE + CreditAccountActivated.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {
            "origination.credit-product-creation-requested",
            "credit-portfolio.credit-account-activated",
            "product-catalog.product-activated",
            "product-catalog.product-retired"
        },
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class CreditAccountActivationFlowIT {

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
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired CreditAccountRepository accountRepository;
    @Autowired com.fintech.creditportfolio.application.port.out.ProductConfigVersionRepository configVersionRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void snapshot_event_activates_credit_account() {
        UUID contractId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("applicationId", contractId.toString());
        payload.put("contractNumber", "CTR-202606-TEST0001");
        payload.put("obligorPartyId", UUID.randomUUID().toString());
        payload.put("productCode", "PL-001");
        payload.put("productVersion", 1);
        payload.put("productType", "PERSONAL_LOAN");
        payload.put("productBehavior", "INSTALLMENT");
        payload.put("approvedAmount", 50000);
        payload.put("approvedLine", null);
        payload.put("assignedTerm", 12);
        payload.put("nominalRate", 24.0);
        payload.put("moratoriumRate", 36.0);
        payload.put("amortizationType", "FRENCH");
        payload.put("openingFeeRate", 3.0);
        payload.put("clabeAccount", "032180000118359719");
        payload.put("riskTier", "BAJO");

        kafkaTemplate.send("origination.credit-product-creation-requested",
                contractId.toString(), payload);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var account = accountRepository.findByContractId(contractId);
            assertThat(account).isPresent();
            assertThat(account.get().getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
            assertThat(account.get().getPrincipalBalance()).isEqualByComparingTo(new BigDecimal("50000"));
            assertThat(account.get().getProductVersion()).isEqualTo(1);
        });
    }

    @Test
    void snapshot_event_is_idempotent() {
        UUID contractId = UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("applicationId", contractId.toString());
        payload.put("contractNumber", "CTR-202606-IDEM0001");
        payload.put("obligorPartyId", UUID.randomUUID().toString());
        payload.put("productCode", "PL-001");
        payload.put("productType", "PERSONAL_LOAN");
        payload.put("productBehavior", "INSTALLMENT");
        payload.put("approvedAmount", 30000);
        payload.put("assignedTerm", 6);
        payload.put("nominalRate", 18.0);
        payload.put("moratoriumRate", 36.0);
        payload.put("amortizationType", "FRENCH");
        payload.put("openingFeeRate", 2.0);
        payload.put("clabeAccount", "032180000118359719");
        payload.put("riskTier", "BAJO");

        // Send twice — idempotency guard skips second activation
        kafkaTemplate.send("origination.credit-product-creation-requested", contractId.toString(), payload);
        kafkaTemplate.send("origination.credit-product-creation-requested", contractId.toString(), payload);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(accountRepository.findByContractId(contractId)).isPresent());

        assertThat(accountRepository.findByContractId(contractId)).isPresent();
    }

    @Test
    void productActivated_propagatesConfig_thenAccountPinsNonDegradedVersion() {
        // 1. Publish the authoritative config version first
        Map<String, Object> caps = new HashMap<>();
        caps.put("hasAmortizationSchedule", true);
        caps.put("hasCreditLimit", false);
        caps.put("allowsMultipleDispositions", false);
        caps.put("dispositionType", "SELF_USE");
        caps.put("hasCutoffDate", false);
        caps.put("hasMinimumPayment", false);
        caps.put("allowsMultipleObligors", false);
        caps.put("commissionsEnabled", false);
        caps.put("requiresBeneficiaryPartyId", false);

        Map<String, Object> configEvent = new HashMap<>();
        configEvent.put("productDefinitionId", UUID.randomUUID().toString());
        configEvent.put("productCode", "ML-PROPAGATE");
        configEvent.put("productVersion", 1);
        configEvent.put("productType", "MICRO_LOAN");
        configEvent.put("behavior", "INSTALLMENT");
        configEvent.put("targetAudience", "B2C");
        configEvent.put("nominalRateAnnual", 0.52);
        configEvent.put("moratoriumRateAnnual", 0.80);
        configEvent.put("openingFeeRate", 0.02);
        configEvent.put("amortizationType", "FRENCH");
        configEvent.put("paymentFrequency", "WEEKLY");
        configEvent.put("amountStep", 500);
        configEvent.put("capabilities", caps);

        kafkaTemplate.send("product-catalog.product-activated", "ML-PROPAGATE", configEvent);

        // 2. Wait until the read-model has the version
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(configVersionRepository.findByCodeAndVersion("ML-PROPAGATE", 1)).isPresent());

        // 3. Now activate an account pinned to that version
        UUID contractId = UUID.randomUUID();
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("eventId", UUID.randomUUID().toString());
        snapshot.put("applicationId", contractId.toString());
        snapshot.put("contractNumber", "CTR-202606-PROP0001");
        snapshot.put("obligorPartyId", UUID.randomUUID().toString());
        snapshot.put("productCode", "ML-PROPAGATE");
        snapshot.put("productVersion", 1);
        snapshot.put("productType", "MICRO_LOAN");
        snapshot.put("productBehavior", "INSTALLMENT");
        snapshot.put("approvedAmount", 5000);
        snapshot.put("assignedTerm", 4);
        snapshot.put("nominalRate", 52.0);
        snapshot.put("moratoriumRate", 80.0);
        snapshot.put("amortizationType", "FRENCH");
        snapshot.put("openingFeeRate", 2.0);
        snapshot.put("clabeAccount", "032180000118359719");
        snapshot.put("riskTier", "BAJO");

        kafkaTemplate.send("origination.credit-product-creation-requested", contractId.toString(), snapshot);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            var account = accountRepository.findByContractId(contractId);
            assertThat(account).isPresent();
            assertThat(account.get().getStatus()).isEqualTo(CreditAccountStatus.ACTIVE);
            assertThat(account.get().getProductVersion()).isEqualTo(1);
        });

        // 4. The pinned config version is the authoritative one (not degraded)
        var version = configVersionRepository.findByCodeAndVersion("ML-PROPAGATE", 1).orElseThrow();
        assertThat(version.isDegraded()).isFalse();
        assertThat(version.getPaymentFrequency()).isEqualTo("WEEKLY");
        assertThat(version.getAmountStep()).isEqualTo(500);
    }
}
