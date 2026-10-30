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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

    // ── Contratos del dominio ────────────────────────────────────────────────

    /** La CLABE ({@code clabe}) ya viene enmascarada de banking. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BankAccount(UUID bankAccountId, UUID companyId, String institutionCode,
                              String institutionName, String clabe, String holderName,
                              String currency, String ledgerAccount, String status) {}

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
