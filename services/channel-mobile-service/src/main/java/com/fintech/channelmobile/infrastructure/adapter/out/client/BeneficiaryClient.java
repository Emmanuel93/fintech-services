package com.fintech.channelmobile.infrastructure.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * La colocación B2B2C, que vive en beneficiary-service.
 *
 * <p>El BFF no traduce estas respuestas: beneficiary-service ya publica el contrato que la app
 * consume, campo por campo. Re-mapearlas aquí sólo agregaría un lugar más donde un renombre puede
 * romper la app sin que ningún test lo note.
 *
 * <p>Lo que sí hace el BFF es lo suyo: ser la única puerta. La app no conoce beneficiary-service
 * ni su puerto, igual que no conoce origination ni scoring.
 */
@Component
public class BeneficiaryClient {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryClient.class);

    private final WebClient webClient;

    public BeneficiaryClient(@Qualifier("beneficiaryWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public JsonNode listPlacements(String userId, String status) {
        return get(userId, uri -> uri.path("/api/v1/placements")
                .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                .build());
    }

    public JsonNode getPlacement(String userId, UUID placementId) {
        return get(userId, uri -> uri.path("/api/v1/placements/{id}").build(placementId));
    }

    public JsonNode bureau(String userId, UUID placementId) {
        return get(userId, uri -> uri.path("/api/v1/placements/{id}/bureau").build(placementId));
    }

    public JsonNode lineSummary(String userId) {
        return get(userId, uri -> uri.path("/api/v1/distributor/line-summary").build());
    }

    public JsonNode beneficiaries(String userId) {
        return get(userId, uri -> uri.path("/api/v1/beneficiaries").build());
    }

    public JsonNode createPlacement(String userId, Map<String, Object> body) {
        return post(userId, "/api/v1/placements", body);
    }

    public JsonNode approve(String userId, UUID placementId, Map<String, Object> body) {
        return post(userId, "/api/v1/placements/" + placementId + "/approve", body);
    }

    public JsonNode reject(String userId, UUID placementId, Map<String, Object> body) {
        return post(userId, "/api/v1/placements/" + placementId + "/reject", body);
    }

    public JsonNode resend(String userId, UUID placementId) {
        return post(userId, "/api/v1/placements/" + placementId + "/resend", Map.of());
    }

    public JsonNode cancel(String userId, UUID placementId, Map<String, Object> body) {
        return post(userId, "/api/v1/placements/" + placementId + "/cancel", body);
    }

    /** Sólo local: cierra el KYC de la beneficiaria en lugar de su web, que aún no existe. */
    public JsonNode simulateKyc(UUID placementId, Map<String, Object> body) {
        return post(null, "/api/v1/internal/test-support/placements/" + placementId + "/complete-kyc",
                body == null ? Map.of() : body);
    }

    private JsonNode get(String userId,
                         java.util.function.Function<org.springframework.web.util.UriBuilder,
                                 java.net.URI> uriFn) {
        return webClient.get()
                .uri(uriFn)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::propagate)
                .bodyToMono(JsonNode.class)
                .block();
    }

    private JsonNode post(String userId, String path, Map<String, Object> body) {
        log.info("-> POST beneficiary-service {}", path);
        WebClient.RequestBodySpec spec = webClient.post().uri(path);
        if (userId != null) spec = spec.header("X-User-Id", userId);
        return spec
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::propagate)
                .bodyToMono(JsonNode.class)
                .block();
    }

    /**
     * El status y el mensaje del servicio llegan intactos a la app.
     *
     * <p>Importa porque los códigos son parte del contrato: 409 es «su verificación sigue en
     * curso» y 422 es «ese monto no lo permite el producto». Colapsarlos en un 500 obligaría a la
     * app a enseñar «algo salió mal» donde hay una explicación que el usuario puede accionar.
     */
    private Mono<? extends Throwable> propagate(org.springframework.web.reactive.function.client.ClientResponse resp) {
        return resp.bodyToMono(String.class).defaultIfEmpty("").flatMap(body ->
                Mono.error(new ResponseStatusException(resp.statusCode(), detailOf(body))));
    }

    private static String detailOf(String body) {
        if (body == null || body.isBlank()) return "beneficiary-service no respondió";
        // Los servicios devuelven ProblemDetail; se rescata `detail` para no enseñarle al usuario
        // un JSON completo dentro de un snackbar.
        int i = body.indexOf("\"detail\":\"");
        if (i < 0) return body;
        int start = i + 10;
        int end = body.indexOf('"', start);
        return end > start ? body.substring(start, end) : body;
    }
}
