package com.fintech.payments.infrastructure.adapter.in.api;

import com.fintech.payments.application.service.BalanceSnapshotService;
import com.fintech.payments.application.service.PaymentService;
import com.fintech.payments.domain.PaymentMethod;
import com.fintech.payments.domain.PaymentOrderNotFoundException;
import com.fintech.payments.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment submission and management with dual-validation balance control")
class PaymentsController {

    private final PaymentService paymentService;
    private final BalanceSnapshotService snapshotService;

    PaymentsController(PaymentService paymentService, BalanceSnapshotService snapshotService) {
        this.paymentService  = paymentService;
        this.snapshotService = snapshotService;
    }

    @PostMapping
    @Operation(summary = "Submit a payment — pre-validates against balance snapshot, async confirms via Kafka")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment submitted (PENDING or PRE-REJECTED)"),
        @ApiResponse(responseCode = "422", description = "Account not found in snapshot or no credit activated")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<PaymentOrderResponse> submit(@Valid @RequestBody SubmitPaymentRequest req) {
        PaymentMethod method = PaymentMethod.valueOf(req.paymentMethod().toUpperCase());
        return ResponseEntity.ok(PaymentOrderResponse.from(
                paymentService.submit(req.creditAccountId(), req.obligorPartyId(),
                        req.amount(), method, req.externalRef())));
    }

    @GetMapping("/accounts/{creditAccountId}")
    @Operation(summary = "List payment orders for a credit account")
    @ApiResponse(responseCode = "200", description = "Payment orders returned")
    ResponseEntity<List<PaymentOrderResponse>> listByAccount(@PathVariable UUID creditAccountId) {
        List<PaymentOrderResponse> result = paymentService.findByCreditAccountId(creditAccountId)
                .stream().map(PaymentOrderResponse::from).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{paymentOrderId}")
    @Operation(summary = "Get a single payment order by ID")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment order returned"),
        @ApiResponse(responseCode = "404", description = "Payment order not found")
    })
    ResponseEntity<PaymentOrderResponse> getById(@PathVariable UUID paymentOrderId) {
        return ResponseEntity.ok(PaymentOrderResponse.from(paymentService.findById(paymentOrderId)));
    }

    @GetMapping("/accounts/{creditAccountId}/balance")
    @Operation(summary = "Query current balance snapshot for a credit account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Balance snapshot returned"),
        @ApiResponse(responseCode = "404", description = "No snapshot found — account may not be activated")
    })
    ResponseEntity<BalanceSnapshotResponse> getBalance(@PathVariable UUID creditAccountId) {
        return snapshotService.findByCreditAccountId(creditAccountId)
                .map(BalanceSnapshotResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new PaymentOrderNotFoundException(
                        "No balance snapshot for creditAccountId=" + creditAccountId));
    }

    @PostMapping("/{paymentOrderId}/reverse")
    @Operation(summary = "Reverse a CONFIRMED payment (SPEI devolution)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Payment reversed"),
        @ApiResponse(responseCode = "422", description = "Payment not in CONFIRMED state")
    })
    @SecurityRequirement(name = "bearerAuth")
    ResponseEntity<PaymentOrderResponse> reverse(@PathVariable UUID paymentOrderId,
                                                  @Valid @RequestBody ReversePaymentRequest req) {
        return ResponseEntity.ok(PaymentOrderResponse.from(
                paymentService.reverse(paymentOrderId, req.reason())));
    }
}
