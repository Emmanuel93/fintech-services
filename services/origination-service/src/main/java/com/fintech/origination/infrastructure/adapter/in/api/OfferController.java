package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.application.PresentOfferCommand;
import com.fintech.origination.application.port.in.AcceptOfferUseCase;
import com.fintech.origination.application.port.in.PresentOfferUseCase;
import com.fintech.origination.application.port.in.RejectOfferUseCase;
import com.fintech.origination.infrastructure.adapter.in.api.dto.CreditApplicationResponse;
import com.fintech.origination.infrastructure.adapter.in.api.dto.PresentOfferRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/origination/applications/{applicationId}/offer")
@Tag(name = "Offer Management", description = "Present, accept and reject credit offers (Phase E)")
class OfferController {

    private final PresentOfferUseCase presentUseCase;
    private final AcceptOfferUseCase acceptUseCase;
    private final RejectOfferUseCase rejectUseCase;

    OfferController(PresentOfferUseCase presentUseCase,
                    AcceptOfferUseCase acceptUseCase,
                    RejectOfferUseCase rejectUseCase) {
        this.presentUseCase = presentUseCase;
        this.acceptUseCase  = acceptUseCase;
        this.rejectUseCase  = rejectUseCase;
    }

    @PostMapping
    @Operation(
            summary = "Present offer to prospect",
            description = "Application must be APPROVED. Reads catalog, computes CAT, embeds pricing snapshot.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Offer presented (OFFER_PRESENTED)"),
                    @ApiResponse(responseCode = "404", description = "Application or product not found"),
                    @ApiResponse(responseCode = "400", description = "Validation / business rule violation")
            })
    ResponseEntity<CreditApplicationResponse> presentOffer(
            @PathVariable UUID applicationId,
            @Valid @RequestBody PresentOfferRequest request) {

        return ResponseEntity.ok(CreditApplicationResponse.from(
                presentUseCase.present(new PresentOfferCommand(
                        applicationId,
                        request.productCode(),
                        request.offeredAmount(),
                        request.offeredTerm()))));
    }

    @PostMapping("/accept")
    @Operation(summary = "Accept offer", description = "Application must be OFFER_PRESENTED and within TTL.")
    ResponseEntity<CreditApplicationResponse> acceptOffer(@PathVariable UUID applicationId) {
        return ResponseEntity.ok(CreditApplicationResponse.from(acceptUseCase.accept(applicationId)));
    }

    @PostMapping("/reject")
    @Operation(summary = "Reject offer")
    ResponseEntity<CreditApplicationResponse> rejectOffer(@PathVariable UUID applicationId) {
        return ResponseEntity.ok(CreditApplicationResponse.from(rejectUseCase.reject(applicationId)));
    }
}
