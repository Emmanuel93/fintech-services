package com.fintech.wallet;

import com.fintech.wallet.application.port.out.WalletViewRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — wallet projects credit-portfolio.credit-account-activated into a local
 * WalletView (WV-01). Verifies the messaging → projection path against a real Postgres and a real
 * (embedded) broker. Complements WalletAcceptanceTest (HTTP contract).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.credit-account-activated", "credit-portfolio.balance-updated",
        "credit-portfolio.installment-due", "credit-portfolio.disposition-completed",
        "wallet.payment-instruction-created", "wallet.disposition-requested",
        "wallet.snapshot-updated", "wallet.withdrawal-completed"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class WalletFlowIT {

    @Autowired WalletViewRepository walletViewRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void activation_projectsWalletView() {
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        Map<String, Object> p = new HashMap<>();
        p.put("eventId", UUID.randomUUID().toString());
        p.put("occurredOn", Instant.now().toString());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("contractId", UUID.randomUUID().toString());
        p.put("obligorPartyId", obligorPartyId.toString());
        p.put("productType", "PERSONAL_LOAN");
        p.put("productBehavior", "INSTALLMENT");
        p.put("principalBalance", 50000);
        p.put("creditLimit", null);

        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var view = walletViewRepository.findByCreditAccountId(creditAccountId);
            assertThat(view).isPresent();
            assertThat(view.get().getObligorPartyId()).isEqualTo(obligorPartyId);
        });
    }
}
