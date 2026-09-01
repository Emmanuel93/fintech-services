package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.application.service.MovementTraceService;
import com.fintech.banking.domain.MovementTrace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * BK-42 · la cadena del dinero, en los dos sentidos.
 *
 * <p>Las cuatro preguntas que hoy exigen abrir cinco servicios, cada una con su endpoint:
 *
 * <pre>
 * GET /traces/by-credit-account/{id}   este crédito, ¿por dónde salió su dinero?
 * GET /traces/by-tracking-key/{clave}  esta clave, ¿de qué crédito era?
 * GET /traces/by-line/{lineId}         este movimiento del banco, ¿a qué corresponde?
 * GET /traces/by-voucher/{ref}         esta póliza, ¿qué dinero real la respalda?
 * GET /traces/incomplete               lo que se quedó a medias, y en qué eslabón
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/traces")
@Tag(name = "Movement Trace", description = "La cadena del dinero, consultable en ambos sentidos")
class MovementTraceController {

    private static final int LIMITE_POR_DEFECTO = 100;

    private final MovementTraceService trazas;

    MovementTraceController(MovementTraceService trazas) { this.trazas = trazas; }

    @GetMapping("/by-credit-account/{creditAccountId}")
    @Operation(summary = "Este crédito, ¿por dónde salió su dinero?")
    List<TraceResponse> porCredito(@PathVariable UUID creditAccountId) {
        return trazas.porCredito(creditAccountId).stream().map(TraceResponse::de).toList();
    }

    @GetMapping("/by-tracking-key/{trackingKey}")
    @Operation(summary = "Esta clave de rastreo, ¿de qué crédito era?")
    ResponseEntity<TraceResponse> porClave(@PathVariable String trackingKey) {
        return trazas.porClaveDeRastreo(trackingKey)
                .map(t -> ResponseEntity.ok(TraceResponse.de(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-line/{lineId}")
    @Operation(summary = "Este movimiento del banco, ¿a qué corresponde?")
    ResponseEntity<TraceResponse> porLinea(@PathVariable UUID lineId) {
        return trazas.porLineaBancaria(lineId)
                .map(t -> ResponseEntity.ok(TraceResponse.de(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-voucher/{voucherRef}")
    @Operation(summary = "Esta póliza, ¿qué dinero real la respalda?")
    List<TraceResponse> porPoliza(@PathVariable String voucherRef) {
        return trazas.porPoliza(voucherRef).stream().map(TraceResponse::de).toList();
    }

    @GetMapping("/incomplete")
    @Operation(summary = "Lo que se quedó a medias, con el eslabón en que se detuvo")
    List<TraceResponse> incompletas(@RequestParam(required = false) Integer limit) {
        return trazas.incompletas(limit != null ? limit : LIMITE_POR_DEFECTO).stream()
                .map(TraceResponse::de).toList();
    }

    /**
     * {@code eslabonFaltante} es lo que hace útil la respuesta.
     *
     * <p>Saber que una traza está incompleta no ayuda; saber que le falta {@code SIN_CONCILIAR} dice
     * exactamente a quién preguntarle.
     */
    record TraceResponse(UUID traceId, String sourceSystem, String sourceReference,
                         UUID creditAccountId, UUID payoutId, UUID bankAccountId,
                         String trackingKey, UUID lineId, String voucherRef,
                         BigDecimal amount, LocalDate businessDate, String status,
                         boolean completa, String eslabonFaltante) {

        static TraceResponse de(MovementTrace t) {
            return new TraceResponse(t.getId(), t.getSourceSystem(), t.getSourceReference(),
                    t.getCreditAccountId(), t.getPayoutId(), t.getBankAccountId(),
                    t.getTrackingKey(), t.getLineId(), t.getVoucherRef(), t.getAmount(),
                    t.getBusinessDate(), t.getStatus(), t.estaCompleta(), t.eslabonFaltante());
        }
    }
}
