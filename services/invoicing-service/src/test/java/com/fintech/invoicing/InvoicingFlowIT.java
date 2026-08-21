package com.fintech.invoicing;

import com.fintech.invoicing.application.port.out.FiscalProfileRepository;
import com.fintech.invoicing.application.port.out.InvoiceRepository;
import com.fintech.invoicing.domain.InvoiceStatus;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration flow — invoicing projects the party fiscal profile and generates the CFDI from an
 * Accounting invoice request, stamping via the stub PAC.
 *
 * FLOW-1  party.fiscal-profile-updated → FiscalProfile local.
 * FLOW-2  fiscal-profile + accounting.invoice-requested → Invoice STAMPED con el receptor real.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {
        "accounting.invoice-requested", "party.fiscal-profile-updated", "invoicing.invoice-generated"
}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class InvoicingFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired InvoiceRepository invoiceRepository;
    @Autowired FiscalProfileRepository fiscalProfileRepository;
    @Autowired KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void flow_fiscalProfileThenInvoiceRequest_generatesStampedInvoice() {
        UUID party = UUID.randomUUID();

        Map<String, Object> profile = new HashMap<>();
        profile.put("partyId", party.toString());
        profile.put("partyType", "INDIVIDUAL");
        profile.put("rfc", "GARJ900101AB1");
        profile.put("taxName", "JUAN GARCIA LOPEZ");
        profile.put("taxRegime", "612");
        profile.put("taxZipCode", "06600");
        profile.put("cfdiUse", "G03");
        kafkaTemplate.send("party.fiscal-profile-updated", party.toString(), profile);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(fiscalProfileRepository.findById(party)).isPresent());

        Map<String, Object> request = new HashMap<>();
        request.put("invoiceRequestId", UUID.randomUUID().toString());
        request.put("obligorPartyId", party.toString());
        request.put("period", "202607");
        request.put("lines", List.of(
                Map.of("concept", "ORDINARY_INTEREST", "creditAccountId", UUID.randomUUID().toString(), "amount", 100, "isIva", false),
                Map.of("concept", "IVA", "creditAccountId", UUID.randomUUID().toString(), "amount", 16, "isIva", true)));
        request.put("subtotal", 100);
        request.put("iva", 16);
        request.put("total", 116);
        kafkaTemplate.send("accounting.invoice-requested", party.toString(), request);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var invoices = invoiceRepository.findByObligorPartyId(party);
            assertThat(invoices).hasSize(1);
            var inv = invoices.get(0);
            assertThat(inv.getStatus()).isEqualTo(InvoiceStatus.STAMPED);
            assertThat(inv.getReceptorRfc()).isEqualTo("GARJ900101AB1");
            assertThat(inv.getFolioFiscal()).isNotNull();
            assertThat(inv.getTotal()).isEqualByComparingTo("116");
            assertThat(inv.getLines()).hasSize(2);
        });
    }
}
