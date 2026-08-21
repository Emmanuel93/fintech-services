package com.fintech.notifications;

import com.fintech.notifications.application.port.out.ApplicationProspectLinkRepository;
import com.fintech.notifications.application.port.out.CreditAccountProgressRepository;
import com.fintech.notifications.application.port.out.NotificationRecordRepository;
import com.fintech.notifications.application.port.out.PartyContactDirectoryRepository;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Flujo v1 completo: prospect-created → offer-presented (correlación + notificación #1) →
 * credit-account-activated (join final de contacto + notificación #2) →
 * balance-updated(SETTLED) (notificación #6). Prueba, entre otras cosas, que NINGÚN paso depende
 * de un job propio — todo reacciona a un mensaje Kafka (NT-12).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "origination.prospect-created", "origination.offer-presented",
        "credit-portfolio.credit-account-activated", "credit-portfolio.disposition-completed",
        "collections.pre-due-reminder-triggered", "payments.payment-applied",
        "credit-portfolio.balance-updated",
        "notifications.notification-sent", "notifications.notification-failed"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class NotificationFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ApplicationProspectLinkRepository applicationLinkRepository;
    @Autowired PartyContactDirectoryRepository partyContactRepository;
    @Autowired CreditAccountProgressRepository progressRepository;
    @Autowired NotificationRecordRepository recordRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void flow_offerToActivationToSettlement_dispatchesTheRightNotifications() {
        UUID prospectId = UUID.randomUUID();
        UUID applicationId = UUID.randomUUID();
        UUID creditAccountId = UUID.randomUUID();
        UUID obligorPartyId = UUID.randomUUID();

        // 1. prospect-created — arma el paso 1 de correlación (sin notificación propia)
        Map<String, Object> prospectCreated = new HashMap<>();
        prospectCreated.put("prospectId", prospectId.toString());
        prospectCreated.put("firstName", "Ana");
        prospectCreated.put("phone", "5511112222");
        prospectCreated.put("email", "ana@example.com");
        kafkaTemplate.send("origination.prospect-created", prospectId.toString(), prospectCreated);

        // 2. offer-presented — #1 Ofertas de crédito (recipient = prospectId, todavía no hay Party)
        Map<String, Object> offerPresented = new HashMap<>();
        offerPresented.put("eventId", "evt-offer-1");
        offerPresented.put("applicationId", applicationId.toString());
        offerPresented.put("prospectId", prospectId.toString());
        offerPresented.put("offeredAmount", 10000);
        offerPresented.put("offeredTerm", 12);
        offerPresented.put("nominalRate", 0.35);
        offerPresented.put("cat", 0.40);
        kafkaTemplate.send("origination.offer-presented", applicationId.toString(), offerPresented);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(applicationLinkRepository.findById(applicationId)).isPresent();
            var records = recordRepository.findByRecipientId(prospectId);
            assertThat(records).anySatisfy(r -> assertThat(r.getEventType()).isEqualTo(EventType.OFFER_PRESENTED));
        });

        // 3. credit-account-activated — join final de contacto + #2 Bienvenida
        Map<String, Object> activated = new HashMap<>();
        activated.put("eventId", "evt-activated-1");
        activated.put("creditAccountId", creditAccountId.toString());
        activated.put("contractId", applicationId.toString());
        activated.put("obligorPartyId", obligorPartyId.toString());
        activated.put("productType", "PERSONAL_LOAN");
        activated.put("creditLimit", 10000);
        activated.put("nominalRate", 0.35);
        kafkaTemplate.send("credit-portfolio.credit-account-activated", creditAccountId.toString(), activated);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(partyContactRepository.findById(obligorPartyId)).isPresent();
            assertThat(partyContactRepository.findById(obligorPartyId).get().getPhone()).isEqualTo("5511112222");
            assertThat(progressRepository.findById(creditAccountId)).isPresent();
            var records = recordRepository.findByRecipientId(obligorPartyId);
            assertThat(records).anySatisfy(r -> assertThat(r.getEventType()).isEqualTo(EventType.WELCOME_ACTIVATED));
        });

        // 4. balance-updated(SETTLED) — #6 Crédito liquidado
        Map<String, Object> settled = new HashMap<>();
        settled.put("eventId", "evt-settled-1");
        settled.put("creditAccountId", creditAccountId.toString());
        settled.put("obligorPartyId", obligorPartyId.toString());
        settled.put("accountStatus", "SETTLED");
        kafkaTemplate.send("credit-portfolio.balance-updated", creditAccountId.toString(), settled);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var records = recordRepository.findByRecipientId(obligorPartyId);
            assertThat(records).anySatisfy(r -> {
                assertThat(r.getEventType()).isEqualTo(EventType.LOAN_SETTLED);
                assertThat(r.getChannel()).isIn(NotificationChannel.PUSH_NOTIFICATION, NotificationChannel.WHATSAPP,
                        NotificationChannel.EMAIL);
            });
        });
    }
}
