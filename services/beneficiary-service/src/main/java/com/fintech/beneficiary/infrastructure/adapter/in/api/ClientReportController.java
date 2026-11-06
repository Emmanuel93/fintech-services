package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.service.ClientReportService;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/** A distributor's clients: what one may take on her line, and her reports to the red list. */
@RestController
@RequestMapping("/api/v1/beneficiaries/{partyId}")
@Tag(name = "Distribuidor", description = "Línea y beneficiarios")
class ClientReportController {

    private final ClientReportService clients;

    ClientReportController(ClientReportService clients) {
        this.clients = clients;
    }

    @Operation(summary = "Lo que tu cliente puede recibir en tu línea",
               description = "Su tipo (nuevo o revolvente), los límites de tu sucursal para él y, con monto y plazo, si "
                           + "Kredius lo autorizaría y por qué no.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/placement-limits")
    ClientReportService.LimitsForHer limits(@PathVariable UUID partyId,
                                            @RequestParam(required = false) BigDecimal amount,
                                            @RequestParam(required = false) Integer termPeriods) {
        return clients.limits(currentPartyId(), partyId, amount, termPeriods);
    }

    @Operation(summary = "Reportar a tu cliente (lista roja)",
               description = "Motivo del catálogo (VOUCHER_UNPAID, REFUSES_TO_PAY, NOT_LOCATABLE, MISCONDUCT, "
                           + "SUSPECTED_FRAUD, OTHER) y comentarios. Mientras la marca siga, nadie le presta.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    CreditPortfolioClient.StoredFlag report(@PathVariable UUID partyId, @RequestBody ReportRequest request) {
        return clients.report(currentPartyId(), partyId, request.reason(), request.comment());
    }

    @Operation(summary = "Levantar tu reporte, con comentario")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/reports/{flagId}/clear")
    CreditPortfolioClient.StoredFlag clear(@PathVariable UUID partyId, @PathVariable UUID flagId,
                                           @RequestBody(required = false) ClearRequest request) {
        return clients.clear(currentPartyId(), partyId, flagId, request == null ? null : request.comment());
    }

    record ReportRequest(String reason, String comment) {}

    record ClearRequest(String comment) {}

    private static UUID currentPartyId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
