package com.fintech.accounting;

import com.fintech.accounting.application.port.out.InvoiceableItemRepository;
import com.fintech.accounting.application.port.out.JournalEntryRepository;
import com.fintech.accounting.domain.InvoiceableItemStatus;
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

import java.time.Instant;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — accounting posts double-entry from credit-portfolio.balance-updated (deriving
 * the amount from the balance delta) and books the IFRS-9 provision delta from risk.assessment-updated.
 *
 * FLOW-1  balance-updated(CHARGE_ORDINARY_INTEREST, totalDebt 0→100) → interés por cobrar / ingresos
 *         (1203/4101) + un InvoiceableItem PENDING.
 * FLOW-2  risk.assessment-updated(provision 150) → gasto estimación / estimación preventiva (5101/1290).
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "credit-portfolio.balance-updated", "risk.assessment-updated",
        "collections.recovery-payment-applied", "wallet.withdrawal-completed",
        "accounting.journal-entry-created", "accounting.invoice-requested", "accounting.reconciliation-alert"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class AccountingFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired JournalEntryRepository journalRepository;
    @Autowired InvoiceableItemRepository invoiceableItemRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    /** La misma que `fintech.accounting.zone`. Si una cambia, la otra tiene que cambiar. */
    private static final java.time.ZoneId ZONA_CONTABLE = java.time.ZoneId.of("America/Mexico_City");

    @Test
    void flow1_charge_postsIncomeEntry_andAccruesInvoiceable() {
        UUID ca = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        // El período, en la MISMA zona en la que contabilidad lo deriva.
        //
        // `YearMonth.now()` usa la zona de la máquina, y contabilidad opera en hora de México
        // (`fintech.accounting.zone`). En la ventana entre el cambio de mes mexicano y el de la
        // máquina, la prueba pedía las partidas de un mes y contabilidad las había archivado en el
        // siguiente: `Expecting actual not to be empty`.
        //
        // Es el mismo error del que el código de producción ya se corrigió, y que su propio javadoc
        // advierte: «el devengo del 31 procesado a las 00:03 del día 1 caía en el mes siguiente».
        // La prueba lo repetía desde fuera.
        String period = YearMonth.now(ZONA_CONTABLE).toString().replace("-", "");

        // La secuencia real de un crédito: primero se dispone el dinero, después devenga.
        //
        // Mandar el cargo suelto sobre una cuenta que contabilidad nunca vio no prueba el devengo:
        // prueba la incorporación de un saldo preexistente, que ahora se asienta contra capital
        // (3901) precisamente para no volver a etiquetar el saldo entero como si fuera el ingreso
        // de ese hecho — el defecto que producía «comisiones de apertura» del tamaño del préstamo.
        kafkaTemplate.send("credit-portfolio.balance-updated", ca.toString(),
                balanceEvent(ca, party, 1000, 0, 1000, "DISPOSITION_THIRD_PARTY_CREDIT", 1));
        kafkaTemplate.send("credit-portfolio.balance-updated", ca.toString(),
                balanceEvent(ca, party, 1000, 100, 1100, "CHARGE_ORDINARY_INTEREST", 2));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = journalRepository.findByCreditAccountId(ca);
            assertThat(entries).anySatisfy(e -> {
                assertThat(e.getDebitAccount()).isEqualTo("1203");
                assertThat(e.getCreditAccount()).isEqualTo("4101");
                assertThat(e.getAmount()).isEqualByComparingTo("100");
            });
            assertThat(invoiceableItemRepository.findByObligorPartyIdAndPeriodAndStatus(party, period, InvoiceableItemStatus.PENDING))
                    .isNotEmpty();
        });
    }

    @Test
    void flow2_riskAssessment_postsProvisionEntry() {
        UUID ca = UUID.randomUUID();
        Map<String, Object> p = new HashMap<>();
        p.put("creditAccountId", ca.toString());
        p.put("obligorPartyId", UUID.randomUUID().toString());
        p.put("provisionAmount", 150);
        p.put("calculatedAt", Instant.now().toString());
        kafkaTemplate.send("risk.assessment-updated", ca.toString(), p);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var entries = journalRepository.findByCreditAccountId(ca);
            assertThat(entries).anySatisfy(e -> {
                assertThat(e.getDebitAccount()).isEqualTo("5101");
                assertThat(e.getCreditAccount()).isEqualTo("1290");
                assertThat(e.getAmount()).isEqualByComparingTo("150");
            });
        });
    }

    /** Un `balance-updated` como el que publica cartera. */
    private static Map<String, Object> balanceEvent(UUID creditAccountId, UUID partyId,
                                                     int principal, int interest, int totalDebt,
                                                     String triggerEvent, int version) {
        Map<String, Object> p = new HashMap<>();
        p.put("eventId", "evt-" + UUID.randomUUID());
        p.put("creditAccountId", creditAccountId.toString());
        p.put("obligorPartyId", partyId.toString());
        p.put("principalBalance", principal);
        p.put("accruedInterestBalance", interest);
        p.put("penaltyBalance", 0);
        p.put("totalDebt", totalDebt);
        p.put("triggerEvent", triggerEvent);
        p.put("accountStatus", "ACTIVE");
        p.put("balanceVersion", version);
        p.put("originUnitCode", "S_TEST");
        return p;
    }
}
