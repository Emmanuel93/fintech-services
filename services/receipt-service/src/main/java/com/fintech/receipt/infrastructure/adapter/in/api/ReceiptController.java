package com.fintech.receipt.infrastructure.adapter.in.api;

import com.fintech.receipt.application.port.out.ReceiptDocumentStore;
import com.fintech.receipt.application.port.out.ReceiptRepository;
import com.fintech.receipt.application.service.ReceiptRegenerationService;
import com.fintech.receipt.application.service.ReceiptRetryService;
import com.fintech.receipt.domain.Receipt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receipts: by line and cycle, or of a party (as the distributor or as the client), and their PDF. Who may see
 * which is the channel's rule (the BFF checks the party against its own); this API answers what it is asked.
 */
@RestController
@RequestMapping("/api/v1/receipts")
@Tag(name = "Recibos", description = "Recibos de pago de los clientes de las distribuidoras")
class ReceiptController {

    private static final int MAX_PAGE = 500;

    private final ReceiptRepository receipts;
    private final ReceiptDocumentStore documents;
    private final ReceiptRetryService retry;
    private final ReceiptRegenerationService regeneration;

    ReceiptController(ReceiptRepository receipts, ReceiptDocumentStore documents, ReceiptRetryService retry,
                      ReceiptRegenerationService regeneration) {
        this.receipts = receipts;
        this.documents = documents;
        this.retry = retry;
        this.regeneration = regeneration;
    }

    @GetMapping
    @Operation(summary = "Recibos por línea y ciclo, o de una persona (como distribuidora o como cliente)")
    List<Receipt> search(@RequestParam(required = false) UUID creditAccountId,
                         @RequestParam(required = false) Integer cycle,
                         @RequestParam(required = false) UUID partyId,
                         @RequestParam(defaultValue = "200") int limit) {
        if (creditAccountId == null && partyId == null) {
            throw new IllegalArgumentException("Pide los recibos de una línea (creditAccountId) o de una persona (partyId)");
        }
        return receipts.search(creditAccountId, cycle, partyId, Math.min(Math.max(1, limit), MAX_PAGE));
    }

    @GetMapping("/{receiptId}")
    ResponseEntity<Receipt> get(@PathVariable UUID receiptId) {
        return receipts.findById(receiptId).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{receiptId}/pdf")
    @Operation(summary = "El PDF del recibo")
    ResponseEntity<byte[]> pdf(@PathVariable UUID receiptId) {
        var receipt = receipts.findById(receiptId);
        var doc = documents.load(receiptId);
        if (receipt.isEmpty() || doc.isEmpty()) return ResponseEntity.notFound().build();
        String name = (receipt.get().folio() != null ? receipt.get().folio() : receiptId.toString()) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(name).build().toString())
                .header("X-Content-SHA256", doc.get().sha256())
                .body(doc.get().content());
    }

    @PostMapping("/retry")
    @Operation(summary = "Reintenta los recibos fallidos o atorados")
    Map<String, Object> retry() {
        return Map.of("generated", retry.retry());
    }

    @PostMapping("/regenerate")
    @Operation(summary = "Vuelve a imprimir recibos generados desde su foto, con el diseño vigente")
    ReceiptRegenerationService.Result regenerate(@RequestBody(required = false) RegenerateRequest request) {
        return regeneration.regenerate(request == null ? null : request.creditAccountId(), request == null ? null : request.cycle());
    }

    @GetMapping("/status")
    @Operation(summary = "Recibos por estado (operación y autoverificación)")
    List<ReceiptRepository.StatusCount> status() {
        return receipts.countByStatus();
    }

    record RegenerateRequest(UUID creditAccountId, Integer cycle) {}
}
