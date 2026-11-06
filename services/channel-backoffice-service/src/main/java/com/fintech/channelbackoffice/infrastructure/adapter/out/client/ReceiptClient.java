package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.fintech.channelbackoffice.infrastructure.adapter.out.client.DomainClientSupport.staffIdentity;

/** The payment receipts of the distributors' clients (receipt-service). */
@Component
public class ReceiptClient {

    private static final Logger log = LoggerFactory.getLogger(ReceiptClient.class);

    private final WebClient webClient;

    public ReceiptClient(@Qualifier("receiptWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public record ReceiptResponse(
            UUID receiptId,
            UUID creditAccountId,
            int cycleNumber,
            UUID beneficiaryPartyId,
            UUID distributorPartyId,
            LocalDate periodStart,
            LocalDate cutoffDate,
            LocalDate paymentDueDate,
            String status,
            String folio,
            String clientName,
            String clientType,
            Integer liveLoans,
            BigDecimal totalDue,
            Long paymentReference,
            String clabe,
            Long documentBytes,
            Instant generatedAt) {}

    public record ReceiptDocument(byte[] bytes, String contentType) {}

    /** The receipts of a party, as the distributor or as the client. */
    public List<ReceiptResponse> byParty(UUID partyId) {
        log.info("-> GET receipt-service /api/v1/receipts?partyId={}", partyId);
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/receipts").queryParam("partyId", partyId).queryParam("limit", 500).build())
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("receipt-service", r))
                .bodyToFlux(ReceiptResponse.class)
                .collectList()
                .block();
    }

    public ReceiptResponse get(UUID receiptId) {
        log.info("-> GET receipt-service /api/v1/receipts/{}", receiptId);
        return webClient.get()
                .uri("/api/v1/receipts/{id}", receiptId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> r.releaseBody().then(Mono.empty()))
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("receipt-service", r))
                .bodyToMono(ReceiptResponse.class)
                .block();
    }

    public ReceiptDocument pdf(UUID receiptId) {
        log.info("-> GET receipt-service /api/v1/receipts/{}/pdf", receiptId);
        var response = webClient.get()
                .uri("/api/v1/receipts/{id}/pdf", receiptId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("receipt-service", r))
                .toEntity(byte[].class)
                .block();
        if (response == null || response.getBody() == null) return null;
        String type = response.getHeaders().getContentType() != null ? response.getHeaders().getContentType().toString() : "application/pdf";
        return new ReceiptDocument(response.getBody(), type);
    }
}
