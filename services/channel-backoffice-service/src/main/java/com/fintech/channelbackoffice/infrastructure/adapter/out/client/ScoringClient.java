package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a scoring-service: la evaluación de riesgo crediticio que la mesa de análisis
 * necesita para revisar una solicitud —el score obtenido y el detalle regla por regla.
 *
 * <p>La evaluación es <b>opcional</b> en la ficha: un prospecto recién capturado puede no
 * tenerla todavía. Por eso un 404 se traduce a {@code null} en vez de propagarse; la ficha
 * se pinta sin el bloque de riesgo, no se cae. El resto de errores sí se propagan.
 */
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);

    private final WebClient webClient;

    public ScoringClient(@Qualifier("scoringWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** Evaluación más reciente del prospecto, o {@code null} si aún no tiene ninguna. */
    public ScoreEvaluationResponse latestEvaluation(UUID prospectId) {
        log.info("-> GET scoring-service /api/v1/scoring/evaluations/{}/latest", prospectId);
        return webClient.get()
                .uri("/api/v1/scoring/evaluations/{prospectId}/latest", prospectId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> Mono.empty())
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("scoring-service", r))
                .bodyToMono(ScoreEvaluationResponse.class)
                .block();
    }

    /**
     * Reporte de buró más reciente del prospecto, o {@code null} si no hay ninguno. Es un
     * passthrough opaco: el BFF no lee sus campos, solo decide —por rol— si lo pide y lo reenvía;
     * por eso se deserializa a mapa en vez de duplicar el DTO del dominio.
     */
    public Map<String, Object> bureauReport(UUID prospectId) {
        log.info("-> GET scoring-service /api/v1/scoring/reports/by-prospect/{}", prospectId);
        return webClient.get()
                .uri("/api/v1/scoring/reports/by-prospect/{prospectId}", prospectId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(s -> s.value() == 404, r -> Mono.empty())
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("scoring-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    /**
     * Catálogo de políticas de scoring activas. Cada producto sigue la política cuyo
     * {@code productTypeIntent} coincide con su tipo (una por tipo de prospecto). Se listan todas y
     * el BFF filtra por producto: el catálogo de políticas es pequeño y estable.
     */
    public List<ScoringPolicyView> listPolicies() {
        log.info("-> GET scoring-service /api/v1/scoring/policies");
        return webClient.get()
                .uri("/api/v1/scoring/policies")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("scoring-service", r))
                .bodyToFlux(ScoringPolicyView.class)
                .collectList()
                .block();
    }

    /**
     * El catálogo de variables del buró que una política puede evaluar, con sus metadatos.
     *
     * <p>Se pasa tal cual: es un catálogo del dominio de riesgo y traducirlo aquí crearía una
     * segunda lista que mantener. Lo que el motor sabe evaluar es exactamente lo que la consola
     * debe poder configurar, ni más ni menos.
     */
    public List<Map<String, Object>> ruleTypes() {
        log.info("-> GET scoring-service /api/v1/scoring/policies/rule-types");
        return webClient.get()
                .uri("/api/v1/scoring/policies/rule-types")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("scoring-service", r))
                .bodyToFlux(new ParameterizedTypeReference<Map<String, Object>>() {})
                .collectList()
                .block();
    }

    /** Da de alta una política. El dominio versiona: crear sobre un par existente sube versión. */
    public Map<String, Object> createPolicy(Map<String, Object> body) {
        log.info("-> POST scoring-service /api/v1/scoring/policies");
        return webClient.post()
                .uri("/api/v1/scoring/policies")
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("scoring-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    /** Una política de scoring: las reglas que suman/descalifican y los umbrales que deciden. */
    public record ScoringPolicyView(
            UUID policyId,
            String prospectType,
            String productTypeIntent,
            String name,
            String description,
            boolean active,
            int version,
            List<RuleView> rules,
            List<ThresholdView> thresholds
    ) {}

    public record RuleView(
            UUID ruleId, String ruleType, String creditType, String operator,
            double thresholdValue, int scoreContribution, boolean disqualifying,
            Integer periodMonths, String description) {}

    public record ThresholdView(UUID thresholdId, String riskLevel, int minScore, String decision) {}

    /** Solo los campos que el backoffice pinta; Jackson ignora el resto del DTO del dominio. */
    public record ScoreEvaluationResponse(
            UUID    evaluationId,
            UUID    prospectId,
            UUID    reportId,
            UUID    policyId,
            Integer totalScore,
            String  riskLevel,
            String  decision,
            Instant evaluatedAt,
            List<RuleEvaluationDetail> ruleDetails
    ) {}

    /** Resultado de una regla del motor: por qué el score quedó donde quedó. */
    public record RuleEvaluationDetail(
            UUID    ruleId,
            String  ruleType,
            String  creditType,
            boolean matched,
            int     scoreApplied,
            boolean disqualifying,
            String  detail
    ) {}
}
