package com.fintech.invoicing.infrastructure.adapter.in.api;

import com.fintech.invoicing.application.port.in.GetInvoiceUseCase;
import com.fintech.invoicing.infrastructure.adapter.in.api.dto.InvoiceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fintech.invoicing.domain.InvoiceStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Invoicing", description = "Facturación CFDI 4.0")
@SecurityRequirement(name = "bearerAuth")
class InvoicingController {

    private final GetInvoiceUseCase getInvoiceUseCase;

    InvoicingController(GetInvoiceUseCase getInvoiceUseCase) { this.getInvoiceUseCase = getInvoiceUseCase; }

    @Operation(summary = "Detalle de una factura (CFDI)")
    @GetMapping("/{invoiceId}")
    ResponseEntity<InvoiceResponse> getById(@PathVariable UUID invoiceId) {
        return ResponseEntity.ok(InvoiceResponse.from(getInvoiceUseCase.getById(invoiceId)));
    }

    /**
     * Búsqueda paginada de facturas.
     *
     * <p>Sin filtros devuelve todas, paginadas. Con {@code creditAccountId} contesta «qué se le
     * facturó a este préstamo» sin conocer al cliente, que es la pregunta que se hace conciliando un
     * contrato — antes había que saber el party primero, y el party de un crédito no es lo que se
     * tiene a la mano.
     */
    @Operation(summary = "Facturas filtrables por período, estatus, cliente y crédito")
    @GetMapping
    ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) UUID creditAccountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        var result = getInvoiceUseCase.search(period, parseStatus(status), partyId, creditAccountId,
                PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", result.getContent().stream().map(InvoiceResponse::from).toList());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }

    /** Un estatus desconocido se trata como «sin filtro»: es mejor devolver de más que un 500. */
    private static InvoiceStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return InvoiceStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
