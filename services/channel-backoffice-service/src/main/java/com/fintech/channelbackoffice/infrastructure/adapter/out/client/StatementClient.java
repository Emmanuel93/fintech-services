package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.fintech.channelbackoffice.infrastructure.adapter.out.client.DomainClientSupport.staffIdentity;

/**
 * Access to statement-service: the statements it generated each night, and their PDF.
 *
 * <p>statement-service is internal and does not decide who sees what: it trusts that the BFF calling it
 * already checked the statement belongs to an account the user has in view. This client only asks; the
 * check lives in the controller.
 */
@Component
public class StatementClient {

    private static final Logger log = LoggerFactory.getLogger(StatementClient.class);

    private final WebClient webClient;

    public StatementClient(@Qualifier("statementWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** What statement-service keeps of a statement (without the document). */
    public record StatementResponse(
            UUID statementId,
            UUID creditAccountId,
            int cycleNumber,
            String productType,
            String productBehavior,
            LocalDate periodStart,
            LocalDate cutoffDate,
            LocalDate paymentDueDate,
            BigDecimal balanceAtCutoff,
            BigDecimal amountDue,
            BigDecimal minimumPayment,
            String status,
            String contractNumber,
            Long documentBytes,
            Instant generatedAt) {}

    /** The PDF as statement-service rendered it, with the file name it gave it. */
    public record StatementDocument(byte[] bytes, String contentType, String filename) {}

    /** An account's statements, newest first. */
    public List<StatementResponse> byAccount(UUID creditAccountId) {
        log.info("-> GET statement-service /api/v1/statements?creditAccountId={}", creditAccountId);
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/statements").queryParam("creditAccountId", creditAccountId).build())
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("statement-service", r))
                .bodyToFlux(StatementResponse.class)
                .collectList()
                .block();
    }

    /** One statement, without its document: to know whose account it is before delivering the PDF. */
    public StatementResponse get(UUID statementId) {
        log.info("-> GET statement-service /api/v1/statements/{}", statementId);
        return webClient.get()
                .uri("/api/v1/statements/{id}", statementId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("statement-service", r))
                .bodyToMono(StatementResponse.class)
                .block();
    }

    /** The PDF; null when the statement has no document (it was not generated). */
    public StatementDocument pdf(UUID statementId) {
        log.info("-> GET statement-service /api/v1/statements/{}/pdf", statementId);
        var response = webClient.get()
                .uri("/api/v1/statements/{id}/pdf", statementId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("statement-service", r))
                .toEntity(byte[].class)
                .block();
        if (response == null || response.getBody() == null) return null;
        var mediaType = response.getHeaders().getContentType();
        String filename = response.getHeaders().getContentDisposition().getFilename();
        return new StatementDocument(response.getBody(),
                mediaType == null ? "application/pdf" : mediaType.toString(), filename);
    }
}
