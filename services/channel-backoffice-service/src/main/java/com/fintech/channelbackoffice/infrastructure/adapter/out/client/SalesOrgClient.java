package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a sales-org-service: la estructura comercial (niveles, unidades) y las asignaciones.
 *
 * <p>El BFF no compone estos datos con otros servicios —sales-org es autocontenido—, así que aquí es
 * un passthrough delgado: reenvía la identidad del empleado y traduce errores. Los cuerpos van y
 * vienen como mapas para no duplicar los DTOs del dominio. La decisión de QUIÉN puede administrar la
 * estructura la toma el controlador del BFF (RBAC del backoffice).
 */
@Component
public class SalesOrgClient {

    private static final Logger log = LoggerFactory.getLogger(SalesOrgClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public SalesOrgClient(@Qualifier("salesOrgWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    // ── Niveles ────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listLevels() {
        return getList("/api/v1/sales-org/levels");
    }

    public Map<String, Object> createLevel(Map<String, Object> body) {
        return postMap("/api/v1/sales-org/levels", body);
    }

    // ── Unidades ───────────────────────────────────────────────────────────────

    public List<Map<String, Object>> listUnits() {
        return getList("/api/v1/sales-org/units");
    }

    /** Fija el IVA de una unidad; su subárbol lo hereda. */
    public Map<String, Object> setUnitVatRate(String code, java.math.BigDecimal rate) {
        return webClient.put()
                .uri(uri -> {
                    var b = uri.path("/api/v1/sales-org/units/by-code/{code}/vat-rate");
                    if (rate != null) b.queryParam("rate", rate);
                    return b.build(code);
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    public Map<String, Object> createUnit(Map<String, Object> body) {
        return postMap("/api/v1/sales-org/units", body);
    }

    public Map<String, Object> getUnit(UUID unitId) {
        return getMap("/api/v1/sales-org/units/" + unitId);
    }

    public List<Map<String, Object>> children(UUID unitId) {
        return getList("/api/v1/sales-org/units/" + unitId + "/children");
    }

    public List<Map<String, Object>> subtree(UUID unitId) {
        return getList("/api/v1/sales-org/units/" + unitId + "/subtree");
    }

    /** partyIds de los distribuidores en el subárbol de la unidad (para acotar la cartera). */
    public List<UUID> distributorsInSubtree(UUID unitId) {
        log.info("-> GET sales-org /units/{}/distributors", unitId);
        List<UUID> ids = webClient.get()
                .uri("/api/v1/sales-org/units/{id}/distributors", unitId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<UUID>>() {})
                .block();
        return ids == null ? List.of() : ids;
    }

    // ── Asignaciones ─────────────────────────────────────────────────────────────

    /** Cuelga una unidad de otro padre. sales-org reescribe la ruta de toda la rama. */
    public Map<String, Object> moveUnit(UUID unitId, UUID parentUnitId) {
        log.info("-> PUT sales-org /units/{}/parent -> {}", unitId, parentUnitId);
        return webClient.put()
                .uri("/api/v1/sales-org/units/{id}/parent", unitId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(java.util.Collections.singletonMap("parentUnitId",
                        parentUnitId == null ? null : parentUnitId.toString()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(MAP)
                .block();
    }

    public Map<String, Object> assign(UUID unitId, Map<String, Object> body) {
        return postMap("/api/v1/sales-org/units/" + unitId + "/assignments", body);
    }

    public List<Map<String, Object>> activeInUnit(UUID unitId) {
        return getList("/api/v1/sales-org/units/" + unitId + "/assignments");
    }

    public List<Map<String, Object>> scope(UUID unitId) {
        return getList("/api/v1/sales-org/units/" + unitId + "/scope");
    }

    /** Unidad actual del asignado, o {@code null} si no tiene una vigente (404). */
    public Map<String, Object> currentAssignment(String assigneeType, UUID assigneeId) {
        log.info("-> GET sales-org /assignments/{}/{}/current", assigneeType, assigneeId);
        return webClient.get()
                .uri("/api/v1/sales-org/assignments/{t}/{id}/current", assigneeType, assigneeId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> Mono.empty())
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(MAP)
                .block();
    }

    public List<Map<String, Object>> assignmentHistory(String assigneeType, UUID assigneeId) {
        return getList("/api/v1/sales-org/assignments/" + assigneeType + "/" + assigneeId + "/history");
    }

    /** Cierra la asignación vigente. {@code true} si cerró alguna (204); {@code false} si no había (404). */
    public boolean endAssignment(String assigneeType, UUID assigneeId) {
        log.info("-> DELETE sales-org /assignments/{}/{}", assigneeType, assigneeId);
        Boolean ended = webClient.delete()
                .uri("/api/v1/sales-org/assignments/{t}/{id}", assigneeType, assigneeId)
                .headers(DomainClientSupport.staffIdentity())
                .exchangeToMono(resp -> {
                    if (resp.statusCode().value() == 404) return Mono.just(false);
                    if (resp.statusCode().isError()) {
                        return DomainClientSupport.propagate("sales-org-service", resp)
                                .flatMap(err -> Mono.<Boolean>error(err));
                    }
                    return resp.releaseBody().thenReturn(true);
                })
                .block();
        return Boolean.TRUE.equals(ended);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<Map<String, Object>> getList(String uri) {
        log.info("-> GET sales-org {}", uri);
        return webClient.get().uri(uri)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(LIST)
                .block();
    }

    private Map<String, Object> getMap(String uri) {
        log.info("-> GET sales-org {}", uri);
        return webClient.get().uri(uri)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(MAP)
                .block();
    }

    private Map<String, Object> postMap(String uri, Map<String, Object> body) {
        log.info("-> POST sales-org {}", uri);
        return webClient.post().uri(uri)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("sales-org-service", r))
                .bodyToMono(MAP)
                .block();
    }
}
