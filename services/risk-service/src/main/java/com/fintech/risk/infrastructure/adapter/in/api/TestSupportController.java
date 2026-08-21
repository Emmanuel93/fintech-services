package com.fintech.risk.infrastructure.adapter.in.api;

import com.fintech.risk.application.service.RiskAssessmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dev-only: dispara bajo demanda el mismo bean @Scheduled que corre a la 01:00.
 * Deshabilitado por default — solo activo con fintech.test-support.enabled=true.
 *
 * <p>Sin esto no había forma de recalcular el riesgo desde una siembra, y la consecuencia no se veía
 * aquí sino tres servicios más allá: {@code risk.assessment-updated} es lo único que hace que
 * contabilidad constituya estimación preventiva, así que las cuentas 1290 y 5101 salían siempre en
 * cero. Un quebranto entonces no consumía reserva —porque no había— y golpeaba resultados entero,
 * que es exactamente el síntoma por el que se revisó este módulo.
 */
@RestController
@RequestMapping("/internal/test-support")
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
class TestSupportController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportController.class);

    private final RiskAssessmentService riskAssessmentService;

    TestSupportController(RiskAssessmentService riskAssessmentService) {
        this.riskAssessmentService = riskAssessmentService;
    }

    @PostMapping("/run-risk-assessment")
    ResponseEntity<Map<String, Object>> runRiskAssessment() {
        log.info("[test-support] Disparando RiskAssessmentJob manualmente");
        RiskAssessmentService.Result r = riskAssessmentService.reassessAll();
        // `skippedNoPolicy` se devuelve porque es el fallo silencioso de este paso: sin política de
        // provisión vigente el recálculo contesta 200 sin publicar nada, y la ausencia de estimación
        // preventiva se descubre después, en la balanza.
        return ResponseEntity.ok(Map.of(
                "totalActive", r.totalActive(),
                "assessed", r.assessed(),
                "skippedNoPolicy", r.skippedNoPolicy(),
                "failed", r.failed()));
    }
}
