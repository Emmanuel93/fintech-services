package com.fintech.scoring.infrastructure.adapter.in.api;

import com.fintech.scoring.application.port.in.FindCirculoReportUseCase;
import com.fintech.scoring.infrastructure.adapter.in.api.dto.CirculoReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reporte de buró para la mesa de análisis del backoffice.
 *
 * <p>Devuelve el reporte más reciente del prospecto —el expediente crediticio que el analista
 * revisa junto con el score—. Es dato sensible; la restricción por rol la aplica el BFF, que es
 * la autoridad de RBAC del backoffice.
 */
@RestController
@RequestMapping("/api/v1/scoring/reports")
public class BureauReportController {

    private final FindCirculoReportUseCase findCirculoReportUseCase;

    public BureauReportController(FindCirculoReportUseCase findCirculoReportUseCase) {
        this.findCirculoReportUseCase = findCirculoReportUseCase;
    }

    @GetMapping("/by-prospect/{prospectId}")
    @Operation(summary = "Reporte de buró más reciente de un prospecto")
    public ResponseEntity<CirculoReportResponse> byProspect(@PathVariable UUID prospectId) {
        return findCirculoReportUseCase.findByProspectId(prospectId)
                .map(CirculoReportResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
