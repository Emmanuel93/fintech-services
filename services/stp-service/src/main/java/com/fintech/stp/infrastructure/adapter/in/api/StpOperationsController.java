package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.stp.application.port.in.FindPaymentOrderUseCase;
import com.fintech.stp.application.port.in.PollSettlementsUseCase;
import com.fintech.stp.infrastructure.adapter.in.api.dto.PaymentOrderResponse;
import com.fintech.stp.infrastructure.adapter.in.api.dto.SettlementObservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Consulta operativa. Interno, vía el BFF de backoffice. */
@RestController
@RequestMapping("/api/v1/stp")
@Tag(name = "STP Operations", description = "Consulta de órdenes y evidencia de liquidación")
class StpOperationsController {

    private final FindPaymentOrderUseCase findPaymentOrder;
    private final PollSettlementsUseCase pollSettlements;

    StpOperationsController(FindPaymentOrderUseCase findPaymentOrder,
                            PollSettlementsUseCase pollSettlements) {
        this.findPaymentOrder = findPaymentOrder;
        this.pollSettlements = pollSettlements;
    }

    @GetMapping("/payment-orders/{paymentRequestId}")
    @Operation(summary = "Consulta una orden por su clave de idempotencia de entrada")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Orden encontrada"),
        @ApiResponse(responseCode = "404", description = "No existe orden para ese paymentRequestId")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<PaymentOrderResponse> getByRequestId(@PathVariable UUID paymentRequestId) {
        return ResponseEntity.ok(PaymentOrderResponse.from(
                findPaymentOrder.findByPaymentRequestId(paymentRequestId)));
    }

    @GetMapping("/payment-orders")
    @Operation(summary = "Lista las órdenes de una empresa en un día Banxico")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<List<PaymentOrderResponse>> listByDate(
            @RequestParam UUID companyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        return ResponseEntity.ok(findPaymentOrder.findByCompanyAndBusinessDate(companyId, businessDate)
                .stream().map(PaymentOrderResponse::from).toList());
    }

    @GetMapping("/settlement-observations")
    @Operation(summary = "Evidencia cruda de lo que STP contestó para una clave de rastreo")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<List<SettlementObservationResponse>> observations(@RequestParam UUID companyId,
                                                                     @RequestParam String trackingKey) {
        return ResponseEntity.ok(findPaymentOrder.findObservations(companyId, trackingKey)
                .stream().map(SettlementObservationResponse::from).toList());
    }

    @PostMapping("/poll")
    @Operation(summary = "Dispara el poller de liquidación fuera de su intervalo")
    @ApiResponse(responseCode = "200", description = "Corrida ejecutada")
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<Map<String, Integer>> poll() {
        return ResponseEntity.ok(Map.of("appliedObservations", pollSettlements.pollInFlightOrders()));
    }
}
