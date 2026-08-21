package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Acceso al catálogo de productos. Es el único módulo del backoffice que ya tiene su CRUD completo. */
@Component
public class CreditProductClient {

    private static final Logger log = LoggerFactory.getLogger(CreditProductClient.class);

    private final WebClient webClient;

    public CreditProductClient(@Qualifier("creditProductWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** Solo los activos (uso interno de cartera/clientes para resolver nombres y tasas). */
    public List<CreditProductResponse> findActive() {
        log.info("-> GET credit-product-service /api/v1/credit-products");
        return webClient.get()
                .uri("/api/v1/credit-products")
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-product-service", r))
                .bodyToFlux(CreditProductResponse.class)
                .collectList()
                .block();
    }

    /** Catálogo completo (cualquier estado) — lo que administra la pantalla de productos. */
    public List<CreditProductResponse> findAll() {
        log.info("-> GET credit-product-service /api/v1/credit-products/all");
        return webClient.get()
                .uri("/api/v1/credit-products/all")
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-product-service", r))
                .bodyToFlux(CreditProductResponse.class)
                .collectList()
                .block();
    }

    /** Crea una nueva versión de producto. El cuerpo ya viene completo con los defaults del BFF. */
    public CreditProductResponse create(Object request) {
        log.info("-> POST credit-product-service /api/v1/credit-products");
        return webClient.post()
                .uri("/api/v1/credit-products")
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-product-service", r))
                .bodyToMono(CreditProductResponse.class)
                .block();
    }

    public CreditProductResponse activate(UUID id) {
        log.info("-> PUT credit-product-service /api/v1/credit-products/{}/activate", id);
        return webClient.put()
                .uri("/api/v1/credit-products/{id}/activate", id)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-product-service", r))
                .bodyToMono(CreditProductResponse.class)
                .block();
    }

    /** El "retire" del backoffice sobre una versión concreta = deprecar esa versión. */
    public CreditProductResponse deprecate(UUID id) {
        log.info("-> PUT credit-product-service /api/v1/credit-products/{}/deprecate", id);
        return webClient.put()
                .uri("/api/v1/credit-products/{id}/deprecate", id)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-product-service", r))
                .bodyToMono(CreditProductResponse.class)
                .block();
    }

    /** Una versión de producto por id — la que abre el modal del catálogo. */
    public CreditProductResponse getById(UUID id) {
        log.info("-> GET credit-product-service /api/v1/credit-products/{}", id);
        return webClient.get()
                .uri("/api/v1/credit-products/{id}", id)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-product-service", r))
                .bodyToMono(CreditProductResponse.class)
                .block();
    }

    public record CreditProductResponse(
            UUID       productDefinitionId,
            String     productCode,
            Integer    productVersion,
            String     productType,
            String     behavior,
            String     name,
            String     description,
            String     status,
            String     targetAudience,
            String     currency,
            BigDecimal nominalRateAnnual,
            BigDecimal moratoriumRateAnnual,
            Integer    minTerm,
            Integer    maxTerm,
            Integer    defaultTerm,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal defaultCreditLine,
            BigDecimal minCreditLine,
            BigDecimal maxCreditLine,
            Integer    amountStep,
            String     amortizationType,
            String     defaultPaymentFrequency,
            Integer    minApprovalScore,
            String     defaultApprovalFlow,
            BigDecimal openingFeeRate,
            BigDecimal prepaymentFeeRate,
            Set<String> eligiblePartyTypes
    ) {}
}
