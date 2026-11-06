package com.fintech.closing.infrastructure.adapter.in.api;

import com.fintech.closing.application.service.cutoff.CutoffRunService;
import com.fintech.closing.application.service.cutoff.CutoffRunService.CutoffRunView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Operación del corte: dispararlo para una fecha, ver cómo va y reintentar las fallidas.
 *
 * <p>Servicio interno: no está publicado en el gateway. El disparo normal es el aviso de cartera de
 * que cerró el día; esto es para reprocesar un día o seguir uno en curso.
 */
@RestController
@RequestMapping("/api/v1/closing/cutoff-runs")
class CutoffRunController {

    private final CutoffRunService cutoffs;

    CutoffRunController(CutoffRunService cutoffs) {
        this.cutoffs = cutoffs;
    }

    /**
     * Planifica y despacha el corte del día, por las mismas compuertas que el aviso de cartera: si
     * cartera no ha cerrado el día, responde 409 con el motivo. Idempotente: sobre uno existente,
     * reenvía lo pendiente.
     */
    @PostMapping
    ResponseEntity<CutoffRunView> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(cutoffs.planWhenReady(businessDate));
    }

    @GetMapping("/{businessDate}")
    ResponseEntity<CutoffRunView> view(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return cutoffs.view(businessDate).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{businessDate}/retry")
    ResponseEntity<CutoffRunView> retry(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(cutoffs.retryFailed(businessDate));
    }
}
