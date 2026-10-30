package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a disbursement-service para Bancos / Tesorería: los pagos salientes y su bitácora.
 *
 * <p>La búsqueda se resuelve <b>en el dueño</b>, paginada: el BFF no trae órdenes para filtrarlas
 * después. La cuenta del beneficiario llega enmascarada.
 */
@Component
public class DisbursementClient {

    private static final Logger log = LoggerFactory.getLogger(DisbursementClient.class);

    private final WebClient webClient;

    public DisbursementClient(@Qualifier("disbursementWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public DisbursementPage search(UUID creditAccountId, String q, int page, int size) {
        log.info("-> GET disbursement /disbursements/search credito={} q={}",
                creditAccountId, q == null ? "" : "«" + q + "»");
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/disbursements/search")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (creditAccountId != null) b.queryParam("creditAccountId", creditAccountId);
                    if (q != null && !q.isBlank()) b.queryParam("q", q.trim());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("disbursement-service", r))
                .bodyToMono(DisbursementPage.class)
                .block();
    }

    public Disbursement findById(UUID disbursementId) {
        log.info("-> GET disbursement /disbursements/{}", disbursementId);
        return webClient.get()
                .uri("/api/v1/disbursements/{id}", disbursementId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("disbursement-service", r))
                .bodyToMono(Disbursement.class)
                .block();
    }

    public List<DisbursementEvent> timeline(UUID disbursementId) {
        log.info("-> GET disbursement /disbursements/{}/events", disbursementId);
        return webClient.get()
                .uri("/api/v1/disbursements/{id}/events", disbursementId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("disbursement-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<DisbursementEvent>>() {})
                .block();
    }

    // ── Contratos del dominio ────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Disbursement(UUID disbursementId, UUID companyId, String sourceSystem,
                               String sourceType, String sourceReference,
                               Map<String, String> sourceMetadata, String beneficiaryName,
                               String beneficiaryAccountMasked, BigDecimal amount, String currency,
                               String rail, String provider, String status, String externalRef,
                               String receiptUrl, String failureCode, String failureReason,
                               Instant createdAt, Instant dispatchedAt, Instant settledAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisbursementPage(List<Disbursement> content, int page, int size,
                                   long totalElements, int totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DisbursementEvent(UUID eventId, String fromStatus, String toStatus,
                                    String reasonCode, String detail, String actor,
                                    Instant occurredAt) {}
}
