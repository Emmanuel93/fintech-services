package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Acceso a collections-service.
 *
 * <p>La bandeja se pagina en el dominio, igual que cartera: traer todos los casos para quedarse con
 * 25 filas mueve la mora entera por la red en cada carga, y la mora sólo crece.
 */
@Component
public class CollectionsClient {

    private static final Logger log = LoggerFactory.getLogger(CollectionsClient.class);

    private final WebClient webClient;

    public CollectionsClient(@Qualifier("collectionsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> QUEUE =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    /**
     * Bandeja de promesas cruzando casos.
     *
     * <p>La consulta la resuelve collections-service con {@code JOIN} al caso; aquí no se itera
     * nada. Es una llamada por página, no una por promesa.
     */
    public Map<String, Object> searchPromises(List<String> status, List<String> bucket,
                                              List<String> agentId, String dueFrom, String dueTo,
                                              Boolean dueToday, int page, int size) {
        log.info("-> GET collections /payment-promises estados={} tramos={} gestores={} hoy={}",
                status, bucket, agentId, dueToday);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/collections/payment-promises")
                            .queryParam("page", page).queryParam("size", size);
                    if (status  != null && !status.isEmpty())  b.queryParam("status", status.toArray());
                    if (bucket  != null && !bucket.isEmpty())  b.queryParam("bucket", bucket.toArray());
                    if (agentId != null && !agentId.isEmpty()) b.queryParam("agentId", agentId.toArray());
                    if (dueFrom != null && !dueFrom.isBlank()) b.queryParam("dueFrom", dueFrom);
                    if (dueTo   != null && !dueTo.isBlank())   b.queryParam("dueTo", dueTo);
                    if (Boolean.TRUE.equals(dueToday))         b.queryParam("dueToday", true);
                    return b.build();
                })
                .headers(staffIdentity())
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError,
                        r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(QUEUE)
                .block();
    }

    /** Bandeja de intentos de contacto cruzando casos. Mismo criterio: una llamada por página. */
    public Map<String, Object> searchContactAttempts(List<String> result, List<String> channel,
                                                     List<String> bucket, List<String> agentId,
                                                     String from, String to, int page, int size) {
        log.info("-> GET collections /contact-attempts resultados={} canales={} tramos={}",
                result, channel, bucket);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/collections/contact-attempts")
                            .queryParam("page", page).queryParam("size", size);
                    if (result  != null && !result.isEmpty())  b.queryParam("result", result.toArray());
                    if (channel != null && !channel.isEmpty()) b.queryParam("channel", channel.toArray());
                    if (bucket  != null && !bucket.isEmpty())  b.queryParam("bucket", bucket.toArray());
                    if (agentId != null && !agentId.isEmpty()) b.queryParam("agentId", agentId.toArray());
                    if (from != null && !from.isBlank()) b.queryParam("from", from);
                    if (to   != null && !to.isBlank())   b.queryParam("to", to);
                    return b.build();
                })
                .headers(staffIdentity())
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::isError,
                        r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(QUEUE)
                .block();
    }

    /**
     * Identidad del empleado, que los servicios de dominio exigen en cabeceras.
     *
     * <p>Aquí importa más que en una consulta: el agente que queda en un contacto o en una promesa
     * sale de {@code Authentication#getName()} del lado del dominio, no del cuerpo. Si estas
     * cabeceras no viajan, la gestión se registra sin autor —o directamente sale 401—.
     */
    private Consumer<org.springframework.http.HttpHeaders> staffIdentity() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String staffUserId = auth == null ? null : String.valueOf(auth.getPrincipal());
        String roles = auth == null ? "" : auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(Collectors.joining(","));
        return h -> {
            if (staffUserId != null && !staffUserId.isBlank()) h.set("X-User-Id", staffUserId);
            if (!roles.isBlank()) h.set("X-Roles", roles);
            h.set("X-Channel", "BACKOFFICE");
        };
    }

    // ── Casos ────────────────────────────────────────────────────────────────

    public PageResponse<CollectionCaseResponse> searchCases(String status, String bucket, String productType,
                                                             String assignedAgentId, Integer minDaysDelinquent,
                                                             int page, int size, String sort) {
        log.info("-> GET collections-service /cases status={} bucket={} page={} size={}", status, bucket, page, size);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/collections/cases")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (status != null && !status.isBlank())                   b.queryParam("status", status);
                    if (bucket != null && !bucket.isBlank())                   b.queryParam("bucket", bucket);
                    if (productType != null && !productType.isBlank())         b.queryParam("productType", productType);
                    if (assignedAgentId != null && !assignedAgentId.isBlank()) b.queryParam("assignedAgentId", assignedAgentId);
                    if (minDaysDelinquent != null)                             b.queryParam("minDaysDelinquent", minDaysDelinquent);
                    if (sort != null && !sort.isBlank())                       b.queryParam("sort", sort);
                    return b.build();
                })
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(new ParameterizedTypeReference<PageResponse<CollectionCaseResponse>>() {})
                .block();
    }

    public CollectionCaseResponse getCase(UUID caseId) {
        log.info("-> GET collections-service /cases/{}", caseId);
        return get("/api/v1/collections/cases/" + caseId, CollectionCaseResponse.class);
    }

    public CollectionCaseResponse getCaseByAccount(UUID creditAccountId) {
        log.info("-> GET collections-service /accounts/{}/case", creditAccountId);
        return get("/api/v1/collections/accounts/" + creditAccountId + "/case", CollectionCaseResponse.class);
    }

    // ── Gestión de un caso ───────────────────────────────────────────────────

    public ContactAttemptsResponse contactAttempts(UUID caseId) {
        return get("/api/v1/collections/cases/" + caseId + "/contact-attempts", ContactAttemptsResponse.class);
    }

    public List<CommunicationHoldResponse> communicationHolds(UUID caseId) {
        return getList("/api/v1/collections/cases/" + caseId + "/communication-holds",
                new ParameterizedTypeReference<List<CommunicationHoldResponse>>() {});
    }

    public List<PaymentPromiseResponse> paymentPromises(UUID caseId) {
        return getList("/api/v1/collections/cases/" + caseId + "/payment-promises",
                new ParameterizedTypeReference<List<PaymentPromiseResponse>>() {});
    }

    public List<CollectionAgreementResponse> agreements(UUID caseId) {
        return getList("/api/v1/collections/cases/" + caseId + "/agreements",
                new ParameterizedTypeReference<List<CollectionAgreementResponse>>() {});
    }

    public List<CollectionAgreementResponse> agreementsAwaitingAuthorization() {
        return getList("/api/v1/collections/agreements/awaiting-authorization",
                new ParameterizedTypeReference<List<CollectionAgreementResponse>>() {});
    }

    public WriteOffRecordResponse writeOffByAccount(UUID creditAccountId) {
        return get("/api/v1/collections/accounts/" + creditAccountId + "/write-off", WriteOffRecordResponse.class);
    }

    public List<BureauReportResponse> bureauReports() {
        return getList("/api/v1/collections/bureau-reports",
                new ParameterizedTypeReference<List<BureauReportResponse>>() {});
    }

    public CollectionsConfigResponse config() {
        return get("/api/v1/collections/config", CollectionsConfigResponse.class);
    }

    // ── Escrituras ───────────────────────────────────────────────────────────

    public ContactAttemptResponse recordContactAttempt(UUID caseId, Map<String, Object> body) {
        log.info("-> POST collections-service /cases/{}/contact-attempts", caseId);
        return post("/api/v1/collections/cases/" + caseId + "/contact-attempts", body, ContactAttemptResponse.class);
    }

    public PaymentPromiseResponse createPaymentPromise(UUID caseId, Map<String, Object> body) {
        log.info("-> POST collections-service /cases/{}/payment-promises", caseId);
        return post("/api/v1/collections/cases/" + caseId + "/payment-promises", body, PaymentPromiseResponse.class);
    }

    public CollectionAgreementResponse proposeAgreement(UUID caseId, Map<String, Object> body) {
        log.info("-> POST collections-service /cases/{}/agreements", caseId);
        return post("/api/v1/collections/cases/" + caseId + "/agreements", body, CollectionAgreementResponse.class);
    }

    public CollectionAgreementResponse acceptAgreement(UUID agreementId) {
        return put("/api/v1/collections/agreements/" + agreementId + "/accept", null, CollectionAgreementResponse.class);
    }

    public CollectionAgreementResponse rejectAgreement(UUID agreementId) {
        return put("/api/v1/collections/agreements/" + agreementId + "/reject", null, CollectionAgreementResponse.class);
    }

    public CollectionAgreementResponse authorizeAgreement(UUID agreementId, Map<String, Object> body) {
        log.info("-> PUT collections-service /agreements/{}/authorize", agreementId);
        return put("/api/v1/collections/agreements/" + agreementId + "/authorize", body, CollectionAgreementResponse.class);
    }

    public void requestWriteOff(UUID caseId, Map<String, Object> body) {
        log.info("-> POST collections-service /cases/{}/request-write-off", caseId);
        webClient.post()
                .uri("/api/v1/collections/cases/{caseId}/request-write-off", caseId)
                .headers(staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("collections-service", r))
                .toBodilessEntity()
                .block();
    }

    public WriteOffRecordResponse approveWriteOff(UUID caseId, Map<String, Object> body) {
        log.info("-> POST collections-service /cases/{}/write-offs", caseId);
        return post("/api/v1/collections/cases/" + caseId + "/write-offs", body, WriteOffRecordResponse.class);
    }

    // ── Verbos ───────────────────────────────────────────────────────────────

    private <T> T get(String path, Class<T> type) {
        return webClient.get().uri(path).headers(staffIdentity()).retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(type).block();
    }

    private <T> List<T> getList(String path, ParameterizedTypeReference<List<T>> type) {
        return webClient.get().uri(path).headers(staffIdentity()).retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(type).block();
    }

    private <T> T post(String path, Object body, Class<T> type) {
        var spec = webClient.post().uri(path).headers(staffIdentity());
        return (body == null ? spec.retrieve() : spec.bodyValue(body).retrieve())
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(type).block();
    }

    private <T> T put(String path, Object body, Class<T> type) {
        var spec = webClient.put().uri(path).headers(staffIdentity());
        return (body == null ? spec.retrieve() : spec.bodyValue(body).retrieve())
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("collections-service", r))
                .bodyToMono(type).block();
    }

    // ── Contratos del dominio ────────────────────────────────────────────────

    public record CollectionCaseResponse(
            UUID caseId, UUID creditAccountId, UUID obligorPartyId, String productType,
            String status, String currentBucket, int daysDelinquent, BigDecimal totalDebt,
            String assignedAgentId, String externalAgencyId, String strategy,
            Instant openedAt, Instant closedAt
    ) {}

    public record ContactAttemptResponse(
            UUID attemptId, UUID caseId, String channel, String result, String origin, String agentId,
            UUID notificationId, Integer dunningStep, Instant attemptedAt
    ) {}

    public record CommunicationHoldResponse(
            UUID holdId, UUID caseId, String reason, Instant heldUntil, UUID sourceId,
            boolean active, Instant releasedAt, String releasedBy, String releaseNote, Instant createdAt
    ) {}

    public record ContactAttemptsResponse(
            List<ContactAttemptResponse> attempts, long todayCount, int maxPerDay
    ) {}

    public record PaymentPromiseResponse(
            UUID promiseId, UUID caseId, BigDecimal amount, LocalDate promisedDate,
            String status, String recordedBy, Instant createdAt
    ) {}

    public record RestructureTermsResponse(
            BigDecimal newNominalRate, Integer newTermMonths, String newAmortizationType
    ) {}

    public record CollectionAgreementResponse(
            UUID agreementId, UUID caseId, UUID creditAccountId, String type, String status,
            BigDecimal originalDebt, BigDecimal forgivenAmount, RestructureTermsResponse newTerms,
            String authorizedBy, String authorizationRef,
            Instant proposedAt, Instant respondedAt, Instant executedAt
    ) {}

    public record WriteOffRecordResponse(
            UUID writeOffId, UUID caseId, UUID creditAccountId,
            BigDecimal principalWrittenOff, BigDecimal interestWrittenOff,
            BigDecimal penaltyWrittenOff, BigDecimal totalWrittenOff,
            String authorizedBy, String authorizationRef, String reason,
            boolean bureauReported, LocalDate writeOffDate
    ) {}

    public record BureauReportResponse(
            UUID reportId, UUID creditAccountId, String eventType, BigDecimal amountReported,
            String status, String bureauReference, Instant createdAt
    ) {}

    public record CollectionsConfigResponse(
            int contactAllowedHoursStart, int contactAllowedHoursEnd, int maxContactAttemptsPerDay,
            BigDecimal maxForgivenessPct, int maxTermExtensionMonths,
            int writeOffThresholdDays, int agreementResponseDays
    ) {}

    /** La página tal como la serializa Spring Data. */
    public record PageResponse<T>(
            List<T> content, int number, int size, long totalElements, int totalPages
    ) {}
}
