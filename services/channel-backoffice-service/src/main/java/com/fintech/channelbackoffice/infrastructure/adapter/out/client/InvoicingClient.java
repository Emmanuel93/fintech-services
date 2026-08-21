package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Acceso a invoicing-service.
 *
 * <p>El filtro por crédito es el que justifica esta clase: la factura consolida los conceptos de
 * <b>un cliente</b>, y sus líneas llevan el préstamo. Preguntar «qué se le facturó a este contrato»
 * antes obligaba a conocer al party primero — y el party de un crédito no es lo que se tiene a la
 * mano cuando se está conciliando un contrato.
 */
@Component
public class InvoicingClient {

    private static final Logger log = LoggerFactory.getLogger(InvoicingClient.class);

    private final WebClient webClient;

    public InvoicingClient(@Qualifier("invoicingWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public InvoicePage search(String period, String status, UUID partyId, UUID creditAccountId,
                              int page, int size) {
        log.info("-> GET invoicing-service /invoices period={} status={} credito={} page={}",
                period, status, creditAccountId, page);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/invoices")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (period != null && !period.isBlank()) b.queryParam("period", period);
                    if (status != null && !status.isBlank()) b.queryParam("status", status);
                    if (partyId != null)                     b.queryParam("partyId", partyId);
                    if (creditAccountId != null)             b.queryParam("creditAccountId", creditAccountId);
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("invoicing-service", r))
                .bodyToMono(InvoicePage.class)
                .block();
    }

    public InvoiceResponse byId(UUID invoiceId) {
        return webClient.get().uri("/api/v1/invoices/" + invoiceId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("invoicing-service", r))
                .bodyToMono(InvoiceResponse.class)
                .block();
    }

    // ── Contratos del dominio ────────────────────────────────────────────────

    public record InvoiceLineResponse(
            String concept, UUID creditAccountId, BigDecimal amount, boolean isIva) {}

    public record InvoiceResponse(
            UUID invoiceId, UUID invoiceRequestId, UUID obligorPartyId, String period,
            String receptorRfc, String receptorName, String receptorRegime, String cfdiUse,
            BigDecimal subtotal, BigDecimal iva, BigDecimal total, String currency, String status,
            UUID folioFiscal, String serie, Long folio, Instant stampedAt,
            List<InvoiceLineResponse> lines) {}

    public record InvoicePage(
            List<InvoiceResponse> content, int page, int size, long totalElements, int totalPages) {}
}
