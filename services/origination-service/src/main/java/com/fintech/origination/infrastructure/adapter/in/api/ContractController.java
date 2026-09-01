package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.application.GenerateContractCommand;
import com.fintech.origination.application.SignContractCommand;
import com.fintech.origination.application.port.in.GenerateContractUseCase;
import com.fintech.origination.application.port.in.SignContractUseCase;
import com.fintech.origination.infrastructure.adapter.in.api.dto.CreditApplicationResponse;
import com.fintech.origination.infrastructure.adapter.in.api.dto.GenerateContractRequest;
import com.fintech.origination.infrastructure.adapter.in.api.dto.SignContractRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/origination/applications/{applicationId}/contract")
@Tag(name = "Contract Management", description = "Generate and sign credit contracts (Phase F)")
class ContractController {

    private final GenerateContractUseCase generateUseCase;
    private final SignContractUseCase signUseCase;

    ContractController(GenerateContractUseCase generateUseCase, SignContractUseCase signUseCase) {
        this.generateUseCase = generateUseCase;
        this.signUseCase     = signUseCase;
    }

    @PostMapping("/generate")
    @Operation(
            summary = "Generate contract",
            description = "Application must be OFFER_ACCEPTED. Creates contract number, transitions to PENDING_SIGNATURE.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Contract generated (PENDING_SIGNATURE)"),
                    @ApiResponse(responseCode = "404", description = "Application not found"),
                    @ApiResponse(responseCode = "400", description = "Invalid state")
            })
    ResponseEntity<CreditApplicationResponse> generateContract(
            @PathVariable UUID applicationId,
            @Valid @RequestBody GenerateContractRequest request) {

        return ResponseEntity.ok(CreditApplicationResponse.from(
                generateUseCase.generate(new GenerateContractCommand(applicationId, request.signatureMethod()))));
    }

    @PostMapping("/sign")
    @Operation(
            summary = "Sign contract",
            description = "Application must be PENDING_SIGNATURE. Validates CLABE + signature, " +
                          "transitions to CONTRACT_SIGNED and publishes CreditProductCreationRequested snapshot.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Contract signed (CONTRACT_SIGNED)"),
                    @ApiResponse(responseCode = "400", description = "Invalid CLABE, invalid signature or wrong state"),
                    @ApiResponse(responseCode = "404", description = "Application not found")
            })
    ResponseEntity<CreditApplicationResponse> signContract(
            @PathVariable UUID applicationId,
            @Valid @RequestBody SignContractRequest request) {

        return ResponseEntity.ok(CreditApplicationResponse.from(
                signUseCase.sign(new SignContractCommand(
                        applicationId,
                        request.clabeAccount(),
                        request.signatureProof(),
                        request.documentRef(),
                        request.bnplDeferralDays()))));
    }
}
