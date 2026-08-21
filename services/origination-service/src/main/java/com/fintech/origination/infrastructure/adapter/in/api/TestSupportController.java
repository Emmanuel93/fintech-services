package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.CreditApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

/**
 * Dev-only: lleva una solicitud a revisión humana sin depender del score.
 *
 * <p>Existe por una razón concreta: la bandeja de comité y las pruebas E2E de
 * decisión necesitan solicitudes en {@code UNDER_MANUAL_REVIEW} o
 * {@code COMMITTEE_REVIEW}, y a esos estados sólo se llega si el motor devuelve
 * MANUAL_REVIEW —score entre 100 y 199—. Fabricar un solicitante que caiga justo
 * en esa franja es adivinar las reglas; esto lo hace determinista.
 *
 * <p>No inventa una transición nueva: usa las mismas del dominio
 * ({@code sendToManualReview} / {@code sendToCommitteeReview}), así que el estado
 * resultante es indistinguible del que produce el flujo real.
 *
 * <p>Apagado por defecto — sólo activo con {@code fintech.test-support.enabled=true},
 * que no está en QA ni en producción.
 */
@RestController
@RequestMapping("/internal/test-support")
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
class TestSupportController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportController.class);

    private final CreditApplicationRepository applicationRepository;

    TestSupportController(CreditApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @PostMapping("/applications/{applicationId}/route-to-review")
    @Transactional
    ResponseEntity<Map<String, Object>> routeToReview(
            @PathVariable UUID applicationId,
            @RequestParam(defaultValue = "false") boolean committee,
            @RequestParam(defaultValue = "MEDIO") String riskLevel) {

        CreditApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "application not found"));

        if (committee) {
            app.sendToCommitteeReview(riskLevel, "MANUAL_REVIEW");
        } else {
            app.sendToManualReview(riskLevel, "MANUAL_REVIEW");
        }
        applicationRepository.save(app);

        log.info("[test-support] Solicitud {} enviada a {}", applicationId,
                committee ? "COMMITTEE_REVIEW" : "UNDER_MANUAL_REVIEW");
        return ResponseEntity.ok(Map.of(
                "applicationId", applicationId.toString(),
                "status", app.getStatus() == null ? null : app.getStatus().name(),
                "riskLevel", riskLevel));
    }

}
