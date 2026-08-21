package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Acceso a commission-service. Para la cartera con alcance: dado el conjunto de distribuidores del
 * subárbol, devuelve los créditos que colocaron (una llamada, acotada por nº de distribuidores).
 */
@Component
public class CommissionClient {

    private static final Logger log = LoggerFactory.getLogger(CommissionClient.class);

    private final WebClient webClient;

    public CommissionClient(@Qualifier("commissionWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** Créditos colocados por un conjunto de distribuidores. Lista vacía si no hay distribuidores. */
    public List<PromoterCredit> creditsByPromoters(Collection<UUID> distributorPartyIds) {
        if (distributorPartyIds == null || distributorPartyIds.isEmpty()) {
            return List.of();
        }
        log.info("-> GET commission-service /promoters/credits ({} distribuidores)", distributorPartyIds.size());
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/commissions/promoters/credits")
                        .queryParam("partyIds", distributorPartyIds.toArray())
                        .build())
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("commission-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<PromoterCredit>>() {})
                .block();
    }

    /**
     * Comisiones devengadas y todavía sin liquidar de un distribuidor.
     *
     * <p>Devuelve lista vacía ante cualquier fallo en vez de propagar. La ficha del distribuidor se
     * arma con cuatro servicios y la comisión es el dato menos crítico de los cuatro: que
     * commission esté caído no debería dejar en blanco la línea, el saldo y los beneficiarios, que
     * sí llegaron.
     */
    public List<CommissionRecord> pendingOf(UUID distributorPartyId) {
        try {
            return webClient.get()
                    .uri("/api/v1/commissions/beneficiaries/{partyId}/pending", distributorPartyId)
                    .headers(DomainClientSupport.staffIdentity())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<List<CommissionRecord>>() {})
                    .block();
        } catch (Exception e) {
            log.warn("commission-service no devolvió comisiones de {}: {}", distributorPartyId, e.getMessage());
            return List.of();
        }
    }

    public record PromoterCredit(UUID creditAccountId, UUID beneficiaryPartyId, String productType) {}

    public record CommissionRecord(
            UUID commissionId,
            String commissionType,
            UUID creditAccountId,
            UUID beneficiaryPartyId,
            java.math.BigDecimal amount,
            String status,
            String period
    ) {}
}
