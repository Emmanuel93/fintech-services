package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Proxy al catálogo de credit-product-service (D4). Sólo lectura: el BFF expone
 * los productos activos para que el prospecto elija al solicitar un crédito.
 */
@Component
public class CreditProductClient {

    private static final Logger log = LoggerFactory.getLogger(CreditProductClient.class);

    private final WebClient webClient;

    public CreditProductClient(@Qualifier("creditProductWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public List<Map<String, Object>> listActiveProducts() {
        log.info("-> GET credit-product-service /api/v1/credit-products");
        return webClient.get()
                .uri("/api/v1/credit-products")
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .doOnNext(r -> log.info("<- credit-product-service {} productos", r.size()))
                .block();
    }

    /** Filtrado por targetAudience (ej. "B2C") — usado por la precalificación del home. */
    public List<Map<String, Object>> listActiveProducts(String targetAudience) {
        log.info("-> GET credit-product-service /api/v1/credit-products?targetAudience={}", targetAudience);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/credit-products")
                        .queryParam("targetAudience", targetAudience)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .doOnNext(r -> log.info("<- credit-product-service {} productos ({})", r.size(), targetAudience))
                .block();
    }
}
