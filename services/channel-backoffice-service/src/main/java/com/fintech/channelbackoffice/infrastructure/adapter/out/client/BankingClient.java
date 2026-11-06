package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Acceso a banking-service para Bancos / Tesorería: las cuentas propias, las rutas de pago y la
 * traza del dinero.
 *
 * <p>Sólo lectura. Las CLABEs llegan ya enmascaradas desde banking —la completa sólo sale por
 * {@code POST /payouts/route}, hacia el conector— y este cliente no tiene forma de pedirla.
 */
@Component
public class BankingClient {

    private static final Logger log = LoggerFactory.getLogger(BankingClient.class);

    private final WebClient webClient;

    public BankingClient(@Qualifier("bankingWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public List<BankAccount> bankAccounts() {
        log.info("-> GET banking /bank-accounts");
        return webClient.get()
                .uri("/api/v1/bank-accounts")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<BankAccount>>() {})
                .block();
    }

    public List<PayoutRoute> payoutRoutes() {
        log.info("-> GET banking /payout-routes");
        return webClient.get()
                .uri("/api/v1/payout-routes")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<PayoutRoute>>() {})
                .block();
    }

    /** La traza de un pago por su id. Vacío si banking todavía no tiene ningún tramo de él. */
    public Optional<Trace> traceByPayout(UUID payoutId) {
        log.info("-> GET banking /traces/by-payout/{}", payoutId);
        try {
            return Optional.ofNullable(webClient.get()
                    .uri("/api/v1/traces/by-payout/{id}", payoutId)
                    .headers(DomainClientSupport.staffIdentity())
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                    .bodyToMono(Trace.class)
                    .block());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return Optional.empty();
            throw e;
        }
    }

    // ── Fondeo ───────────────────────────────────────────────────────────────

    public List<FundingSource> fundingSources() {
        return webClient.get().uri("/api/v1/funding/sources")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<FundingSource>>() {})
                .block();
    }

    public List<FundingMovement> fundingMovements(UUID sourceId) {
        return webClient.get().uri("/api/v1/funding/sources/{id}/movements", sourceId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<FundingMovement>>() {})
                .block();
    }

    /** Registers a capital contribution or a funding line ({@code kind} is {@code capital} or {@code credit-lines}). */
    public FundingSource registerFunding(String kind, Map<String, Object> body) {
        return webClient.post().uri("/api/v1/funding/sources/" + kind)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(FundingSource.class)
                .block();
    }

    public List<FundingMovement> recordFunding(UUID sourceId, Map<String, Object> body) {
        return webClient.post().uri("/api/v1/funding/sources/{id}/movements", sourceId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<FundingMovement>>() {})
                .block();
    }

    public List<FundingMovement> accrueFunding(LocalDate through) {
        return webClient.post().uri(u -> u.path("/api/v1/funding/accruals").queryParam("through", through).build())
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("banking-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<FundingMovement>>() {})
                .block();
    }

    // ── Contratos del dominio ────────────────────────────────────────────────

    /** La CLABE ({@code clabe}) ya viene enmascarada de banking. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BankAccount(UUID bankAccountId, UUID companyId, String institutionCode,
                              String institutionName, String clabe, String holderName,
                              String currency, String ledgerAccount, String status) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FundingSource(UUID fundingSourceId, String kind, String counterpartyType, String counterpartyName,
                                String counterpartyRfc, String contractReference, UUID bankAccountId,
                                BigDecimal authorizedAmount, BigDecimal annualRate, BigDecimal openingFee,
                                boolean openingFeePaid, LocalDate startDate, LocalDate maturityDate, String status,
                                BigDecimal contributed, BigDecimal principal, BigDecimal interestPayable,
                                LocalDate accruedThrough, BigDecimal available) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FundingMovement(UUID fundingMovementId, UUID fundingSourceId, String type, BigDecimal amount,
                                  LocalDate valueDate, LocalDate accruedFrom, LocalDate accruedThrough,
                                  String reference, BigDecimal contributedAfter, BigDecimal principalAfter,
                                  BigDecimal interestPayableAfter, String recordedBy, Instant recordedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayoutRoute(UUID payoutRouteId, UUID companyId, String rail, String provider,
                              UUID bankAccountId, BigDecimal minAmount, BigDecimal maxAmount,
                              int priority, boolean enabled) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trace(UUID traceId, String sourceSystem, String sourceReference,
                        UUID creditAccountId, UUID payoutId, UUID bankAccountId,
                        String trackingKey, UUID lineId, String voucherRef,
                        BigDecimal amount, LocalDate businessDate, String status,
                        boolean complete, String missingLink,
                        OffsetDateTime createdAt, OffsetDateTime routedAt,
                        OffsetDateTime dispatchedAt, OffsetDateTime settledAt,
                        OffsetDateTime reconciledAt, OffsetDateTime postedAt) {}
}
