package com.fintech.invoicing;

/**
 * Acceptance criteria — invoicing-service (HTTP end-to-end, real Postgres + Kafka)
 *
 * AC-1  GET /invoices/{id} no token   → 401
 * AC-2  GET /invoices/{id} seeded     → 200 STAMPED
 * AC-3  GET /invoices/{unknown}       → 404
 * AC-4  GET /invoices?partyId=        → 200
 */

import com.fintech.invoicing.application.port.out.InvoiceRepository;
import com.fintech.invoicing.domain.Invoice;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {
        "accounting.invoice-requested", "party.fiscal-profile-updated", "invoicing.invoice-generated"
})
class InvoicingAcceptanceTest {

    @Autowired TestRestTemplate restTemplate;
    @Autowired InvoiceRepository invoiceRepository;

    private Invoice seedInvoice(UUID partyId) {
        Invoice inv = Invoice.draft(UUID.randomUUID(), partyId, "202607",
                "GARJ900101AB1", "JUAN GARCIA", "612", "06600", "G03",
                new BigDecimal("100"), new BigDecimal("16"), new BigDecimal("116"), "MXN");
        inv.addLine("ORDINARY_INTEREST", UUID.randomUUID(), new BigDecimal("100"), false);
        inv.markStamped(UUID.randomUUID(), "A", 1L);
        return invoiceRepository.save(inv);
    }

    @Test
    void ac1_noToken_returns401() {
        var resp = restTemplate.exchange("/api/v1/invoices/" + UUID.randomUUID(),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ac2_getById_returns200Stamped() {
        Invoice inv = seedInvoice(UUID.randomUUID());
        var resp = getMap("/api/v1/invoices/" + inv.getInvoiceId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("STAMPED");
        assertThat(resp.getBody().get("receptorRfc")).isEqualTo("GARJ900101AB1");
    }

    @Test
    void ac3_getById_unknown_returns404() {
        var resp = getMap("/api/v1/invoices/" + UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * El listado pasó a ser <b>paginado</b>, como el resto de los listados del sistema.
     *
     * <p>Devolvía una lista desnuda con todas las facturas del cliente, que es aceptable mientras un
     * cliente tenga tres y deja de serlo en cuanto tenga tres años de facturación mensual. El único
     * consumidor es el BFF, y se escribió ya contra la forma paginada.
     */
    @Test
    void ac4_byParty_returnsPagedResult() {
        UUID party = UUID.randomUUID();
        seedInvoice(party);
        var resp = getMap("/api/v1/invoices?partyId=" + party);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKeys("content", "page", "size", "totalElements", "totalPages");
        assertThat((java.util.List<?>) resp.getBody().get("content")).isNotEmpty();
    }

    /** El filtro que abre la pregunta «qué se le facturó a este préstamo» sin conocer al cliente. */
    @Test
    void ac5_byCreditAccount_findsTheInvoiceThroughItsLines() {
        UUID party = UUID.randomUUID();
        var seeded = seedInvoice(party);
        UUID creditAccountId = seeded.getLines().get(0).getCreditAccountId();

        var resp = getMap("/api/v1/invoices?creditAccountId=" + creditAccountId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) resp.getBody().get("content")).isNotEmpty();
    }

    private ResponseEntity<Map<String, Object>> getMap(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private ResponseEntity<List<Map<String, Object>>> getList(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(userHeaders()),
                new ParameterizedTypeReference<>() {});
    }

    private HttpHeaders userHeaders() {
        var h = new HttpHeaders();
        h.set("X-User-Id", UUID.randomUUID().toString());
        h.set("X-Roles", "BILLING");
        return h;
    }
}
