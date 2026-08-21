package com.fintech.origination.infrastructure.adapter.out.salesorg;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.origination.application.port.out.PromoterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Resuelve el promoterCode contra sales-org ({@code GET /distributors/by-code/{code}}). Un 404 =
 * código inexistente → vacío (el servicio de originación decide rechazar). Otros errores de
 * transporte se registran y devuelven vacío: es preferible rechazar una solicitud con distribuidor
 * no resoluble que activar un crédito sin comisión (CM-07).
 */
@Component
public class RestClientPromoterResolverAdapter implements PromoterResolver {

    private static final Logger log = LoggerFactory.getLogger(RestClientPromoterResolverAdapter.class);

    private final RestClient salesOrgRestClient;

    public RestClientPromoterResolverAdapter(@Qualifier("salesOrgRestClient") RestClient salesOrgRestClient) {
        this.salesOrgRestClient = salesOrgRestClient;
    }

    @Override
    public Optional<UUID> resolveDistributor(String promoterCode) {
        try {
            ResolveResponse response = salesOrgRestClient.get()
                    .uri("/api/v1/sales-org/distributors/by-code/{code}", promoterCode)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> { /* 404 → cae a null */ })
                    .body(ResolveResponse.class);
            return Optional.ofNullable(response).map(ResolveResponse::partyId);
        } catch (Exception e) {
            log.error("sales-org no resolvió promoterCode='{}': {}", promoterCode, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResolveResponse(UUID partyId) {}
}
