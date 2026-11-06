package com.fintech.statement.infrastructure.adapter.in.api;

import com.fintech.statement.application.port.out.StatementDocumentStore;
import com.fintech.statement.application.port.out.StatementRepository;
import com.fintech.statement.application.service.StatementBatchService;
import com.fintech.statement.application.service.StatementBatchService.Progress;
import com.fintech.statement.application.service.StatementRegenerationService;
import com.fintech.statement.application.service.StatementRetryService;
import com.fintech.statement.application.service.StatementRetryService.RetryResult;
import com.fintech.statement.domain.Statement;
import com.fintech.statement.domain.StatementStatus;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Consultar estados de cuenta, descargar su PDF y seguir la generación de un corte.
 */
@RestController
@RequestMapping("/api/v1/statements")
class StatementController {

    private static final int MAX_LIST = 60;

    private final StatementRepository statements;
    private final StatementDocumentStore documents;
    private final StatementBatchService batches;
    private final StatementRetryService retry;
    private final StatementRegenerationService regeneration;

    StatementController(StatementRepository statements, StatementDocumentStore documents,
                        StatementBatchService batches, StatementRetryService retry,
                        StatementRegenerationService regeneration) {
        this.statements = statements;
        this.documents  = documents;
        this.batches    = batches;
        this.regeneration = regeneration;
        this.retry      = retry;
    }

    /** Por cuenta o por titular, del más reciente al más viejo. */
    @GetMapping
    ResponseEntity<List<Statement>> list(@RequestParam(required = false) UUID creditAccountId,
                                         @RequestParam(required = false) UUID partyId) {
        if (creditAccountId != null) return ResponseEntity.ok(statements.findByAccount(creditAccountId, MAX_LIST));
        if (partyId != null) return ResponseEntity.ok(statements.findByParty(partyId, MAX_LIST));
        return ResponseEntity.badRequest().build();
    }

    @GetMapping("/{statementId}")
    ResponseEntity<Statement> get(@PathVariable UUID statementId) {
        return statements.findById(statementId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{statementId}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable UUID statementId) {
        Statement s = statements.findById(statementId).orElse(null);
        if (s == null || s.status() != StatementStatus.GENERATED) return ResponseEntity.notFound().build();
        return documents.load(statementId)
                .map(doc -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(doc.contentType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                                .filename("estado-de-cuenta-" + (s.contractNumber() == null ? s.creditAccountId() : s.contractNumber())
                                          + "-" + s.cutoffDate() + ".pdf").build().toString())
                        .header("X-Content-SHA256", doc.sha256())
                        .body(doc.content()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Cuántos estados de cuenta del corte se esperan y cuántos van, por producto. */
    @GetMapping("/batches/{runId}")
    ResponseEntity<Progress> batch(@PathVariable UUID runId) {
        return batches.progress(runId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/batches")
    ResponseEntity<Progress> batchByDate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return batches.progressByDate(businessDate).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    /** Operación: reintenta ahora lo fallido en vez de esperar al job. */
    @PostMapping("/retry")
    ResponseEntity<RetryResult> retryFailed() {
        return ResponseEntity.ok(retry.retry());
    }

    /**
     * Operación: vuelve a producir los PDF ya emitidos con la plantilla de hoy y los datos de entonces
     * (uno, los de una cuenta, o todos). La fecha de emisión y las cifras no cambian; no se notifica.
     */
    @PostMapping("/regenerate")
    ResponseEntity<StatementRegenerationService.Result> regenerate(@RequestParam(required = false) UUID statementId,
                                                                   @RequestParam(required = false) UUID creditAccountId) {
        if (statementId != null) {
            return ResponseEntity.ok(new StatementRegenerationService.Result(0, 0, 0).plus(regeneration.regenerate(statementId)));
        }
        return ResponseEntity.ok(regeneration.regenerateAll(creditAccountId));
    }
}
