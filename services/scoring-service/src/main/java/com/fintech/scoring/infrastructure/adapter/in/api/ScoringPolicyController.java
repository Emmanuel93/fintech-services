package com.fintech.scoring.infrastructure.adapter.in.api;

import com.fintech.scoring.application.service.ScoringPolicyService;
import com.fintech.scoring.application.service.ScoringPolicyService.RuleSpec;
import com.fintech.scoring.application.service.ScoringPolicyService.ThresholdSpec;
import com.fintech.scoring.domain.RuleType;
import com.fintech.scoring.domain.ScoringPolicy;
import com.fintech.scoring.infrastructure.adapter.in.api.dto.ScoringPolicyRequest;
import com.fintech.scoring.infrastructure.adapter.in.api.dto.ScoringPolicyResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scoring/policies")
public class ScoringPolicyController {

    private final ScoringPolicyService policyService;

    public ScoringPolicyController(ScoringPolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    public List<ScoringPolicyResponse> listActive() {
        return policyService.listActive().stream()
                .map(ScoringPolicyResponse::from)
                .toList();
    }

    /**
     * Qué variables del buró se pueden configurar, con sus metadatos.
     *
     * <p>Existe para que la pantalla de alta de políticas no lleve el catálogo escrito a mano. Con
     * veinte variables, una lista duplicada en el front se desalinea a la primera que se agrega:
     * el motor evalúa una que la consola no ofrece, o la consola ofrece una que el motor no sabe
     * calcular y la regla queda muerta sin que nadie lo note.
     */
    @GetMapping("/rule-types")
    public List<Map<String, Object>> ruleTypes() {
        return RuleType.catalogo().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tipo", t.name());
            m.put("dimension", t.dimension().name());
            m.put("etiqueta", t.etiqueta());
            m.put("unidad", t.unidad().name());
            m.put("explicacion", t.explicacion());
            m.put("aplicaTipoCredito", t.aplicaTipoCredito());
            m.put("aplicaPeriodo", t.aplicaPeriodo());
            return m;
        }).toList();
    }

    @GetMapping("/{policyId}")
    public ResponseEntity<ScoringPolicyResponse> getById(@PathVariable UUID policyId) {
        return policyService.findById(policyId)
                .map(ScoringPolicyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScoringPolicyResponse create(@Valid @RequestBody ScoringPolicyRequest req) {
        List<RuleSpec> rules = req.rules().stream()
                .map(r -> new RuleSpec(r.ruleType(), r.creditType(), r.operator(),
                        r.thresholdValue(), r.scoreContribution(),
                        r.disqualifying(), r.periodMonths(), r.description()))
                .toList();

        List<ThresholdSpec> thresholds = req.thresholds().stream()
                .map(t -> new ThresholdSpec(t.riskLevel(), t.minScore(), t.decision()))
                .toList();

        ScoringPolicy created = policyService.create(req.prospectType(), req.productTypeIntent(),
                req.name(), req.description(), rules, thresholds);

        return ScoringPolicyResponse.from(created);
    }

    @DeleteMapping("/{policyId}")
    public ResponseEntity<ScoringPolicyResponse> deactivate(@PathVariable UUID policyId) {
        return policyService.deactivate(policyId)
                .map(ScoringPolicyResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
