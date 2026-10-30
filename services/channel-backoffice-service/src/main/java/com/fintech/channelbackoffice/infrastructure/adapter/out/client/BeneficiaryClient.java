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
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a beneficiary-service para la mesa de KYC.
 *
 * <p>Pega a {@code /api/v1/backoffice/placements}, no a {@code /api/v1/placements}: aquella ruta
 * es la de la app del distribuidor y deriva la distribuidora del token, así que desde aquí
 * devolvería la cartera de nadie. La bandeja transversal tiene su propia raíz precisamente para
 * que las dos preguntas no se confundan.
 *
 * <p>La consulta se resuelve <b>en el dueño</b>, paginada: el BFF no trae filas para filtrarlas
 * después ni itera colocaciones para completarlas.
 */
@Component
public class BeneficiaryClient {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public BeneficiaryClient(@Qualifier("beneficiaryWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public Map<String, Object> searchPlacements(Collection<UUID> distributorPartyIds,
                                                List<String> statuses,
                                                List<String> identityDecisions,
                                                Integer stalledDays,
                                                int page, int size) {
        log.info("-> GET beneficiary-service /backoffice/placements distribuidoras={} estados={} veredicto={} atoradas>={}d",
                distributorPartyIds == null ? 0 : distributorPartyIds.size(),
                statuses, identityDecisions, stalledDays);

        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/backoffice/placements")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (distributorPartyIds != null && !distributorPartyIds.isEmpty()) {
                        b.queryParam("distributorPartyId", distributorPartyIds.toArray());
                    }
                    if (statuses != null && !statuses.isEmpty()) {
                        b.queryParam("status", statuses.toArray());
                    }
                    if (identityDecisions != null && !identityDecisions.isEmpty()) {
                        b.queryParam("identityDecision", identityDecisions.toArray());
                    }
                    if (stalledDays != null) b.queryParam("stalledDays", stalledDays);
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("beneficiary-service", r))
                .bodyToMono(MAP)
                .block();
    }

    /** Registra el veredicto de identidad de la mesa de KYC. */
    public Map<String, Object> reviewIdentity(UUID placementId, String decision, String decidedBy,
                                              String rejectionReason, String verificationSource) {
        log.info("-> POST beneficiary-service /backoffice/placements/{}/identity-review {} por {}",
                placementId, decision, decidedBy);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("decision", decision);
        body.put("decidedBy", decidedBy);
        if (rejectionReason != null)   body.put("rejectionReason", rejectionReason);
        if (verificationSource != null) body.put("verificationSource", verificationSource);

        return webClient.post()
                .uri("/api/v1/backoffice/placements/{id}/identity-review", placementId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("beneficiary-service", r))
                .bodyToMono(MAP)
                .block();
    }

    public Map<String, Object> placementDetail(UUID placementId) {
        log.info("-> GET beneficiary-service /backoffice/placements/{}", placementId);
        return webClient.get()
                .uri("/api/v1/backoffice/placements/{id}", placementId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("beneficiary-service", r))
                .bodyToMono(MAP)
                .block();
    }

    /**
     * Los bytes de una pieza de evidencia de la beneficiaria —INE o selfie— que capturó en la web
     * de su liga. Viven en beneficiary y no en origination porque se capturan antes de que exista
     * su prospecto.
     */
    public OriginationClient.DocumentContent documentContent(UUID placementId, String documentType) {
        log.info("-> GET beneficiary-service /backoffice/placements/{}/documents/{}/file", placementId, documentType);
        var response = webClient.get()
                .uri("/api/v1/backoffice/placements/{id}/documents/{type}/file", placementId, documentType)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("beneficiary-service", r))
                .toEntity(byte[].class)
                .block();
        if (response == null || response.getBody() == null) return null;
        var mediaType = response.getHeaders().getContentType();
        return new OriginationClient.DocumentContent(response.getBody(),
                mediaType == null ? "application/octet-stream" : mediaType.toString());
    }
}
