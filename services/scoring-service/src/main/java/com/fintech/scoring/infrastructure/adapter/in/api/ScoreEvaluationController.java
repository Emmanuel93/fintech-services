package com.fintech.scoring.infrastructure.adapter.in.api;

import com.fintech.scoring.application.service.ScoringEvaluationService;
import com.fintech.scoring.infrastructure.adapter.in.api.dto.PrequalificationResponse;
import com.fintech.scoring.infrastructure.adapter.in.api.dto.PrequalifyRequest;
import com.fintech.scoring.infrastructure.adapter.in.api.dto.ScoreEvaluationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scoring")
public class ScoreEvaluationController {

    private final ScoringEvaluationService evaluationService;

    public ScoreEvaluationController(ScoringEvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /** Obtiene la evaluación más reciente de un prospecto. */
    @GetMapping("/evaluations/{prospectId}/latest")
    public ResponseEntity<ScoreEvaluationResponse> getLatest(@PathVariable UUID prospectId) {
        return evaluationService.findLatestEvaluation(prospectId)
                .map(ScoreEvaluationResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Re-evalúa manualmente un prospecto con la política activa para el tipo de producto dado.
     * Útil para casos MEDIO que requieren revisión y re-evaluación.
     */
    @PostMapping("/evaluate/{prospectId}")
    public ScoreEvaluationResponse reevaluate(
            @PathVariable UUID prospectId,
            @RequestParam String prospectType,
            @RequestParam String productTypeIntent) {
        return ScoreEvaluationResponse.from(
                evaluationService.evaluateForProspect(prospectId, prospectType, productTypeIntent));
    }

    /**
     * Precalifica un prospecto contra varios tipos de producto de una sola vez (home "créditos
     * disponibles"). Solo lectura sobre el motor de reglas — no crea score_evaluations ni publica
     * scoring.scoring-completed, y cachea el resultado (ver ScoringEvaluationService.prequalify).
     */
    @PostMapping("/prequalify/{prospectId}")
    public PrequalificationResponse prequalify(
            @PathVariable UUID prospectId,
            @RequestParam String prospectType,
            @RequestBody PrequalifyRequest request) {
        return PrequalificationResponse.from(
                evaluationService.prequalify(prospectId, prospectType, request.productTypes()));
    }
}
