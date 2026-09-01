package com.fintech.wallet.infrastructure.adapter.in.api;

import com.fintech.wallet.application.CreatePaymentInstructionCommand;
import com.fintech.wallet.application.RequestDispositionCommand;
import com.fintech.wallet.application.WithdrawFromWalletCommand;
import com.fintech.wallet.application.port.in.CreatePaymentInstructionUseCase;
import com.fintech.wallet.application.port.in.GetWalletMovementsUseCase;
import com.fintech.wallet.application.port.in.GetWalletViewUseCase;
import com.fintech.wallet.application.port.in.RequestDispositionUseCase;
import com.fintech.wallet.application.port.in.WithdrawFromWalletUseCase;
import com.fintech.wallet.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet", description = "Balance projection and payment instruction management")
class WalletController {

    private final GetWalletViewUseCase getWalletViewUseCase;
    private final GetWalletMovementsUseCase getWalletMovementsUseCase;
    private final CreatePaymentInstructionUseCase createInstructionUseCase;
    private final RequestDispositionUseCase requestDispositionUseCase;
    private final WithdrawFromWalletUseCase withdrawFromWalletUseCase;

    WalletController(GetWalletViewUseCase getWalletViewUseCase,
                     GetWalletMovementsUseCase getWalletMovementsUseCase,
                     CreatePaymentInstructionUseCase createInstructionUseCase,
                     RequestDispositionUseCase requestDispositionUseCase,
                     WithdrawFromWalletUseCase withdrawFromWalletUseCase) {
        this.getWalletViewUseCase    = getWalletViewUseCase;
        this.getWalletMovementsUseCase = getWalletMovementsUseCase;
        this.createInstructionUseCase = createInstructionUseCase;
        this.requestDispositionUseCase = requestDispositionUseCase;
        this.withdrawFromWalletUseCase = withdrawFromWalletUseCase;
    }

    @Operation(summary = "Get wallet view for a credit account")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Wallet view returned"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "404", description = "Wallet not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{creditAccountId}")
    ResponseEntity<WalletViewResponse> getWalletView(@PathVariable UUID creditAccountId) {
        var view = getWalletViewUseCase.getByCreditAccountId(creditAccountId);
        return ResponseEntity.ok(WalletViewResponse.from(view));
    }

    @Operation(summary = "List all credit instruments (wallets) for a party",
            description = "General wallet view — every active credit product the party holds, each typed by productType.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Instruments returned (possibly empty)"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(params = "partyId")
    ResponseEntity<List<WalletViewResponse>> listByParty(@RequestParam UUID partyId) {
        return ResponseEntity.ok(getWalletViewUseCase.listByPartyId(partyId).stream()
                .map(WalletViewResponse::from)
                .toList());
    }

    @Operation(summary = "Movimientos del wallet (historial)",
            description = "Ledger unificado: disposiciones (crédito), retiros y pagos (débito), más reciente primero.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Movimientos (posiblemente vacío)"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{creditAccountId}/movements")
    ResponseEntity<List<WalletMovementResponse>> movements(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(getWalletMovementsUseCase.getMovementsByCreditAccountId(creditAccountId).stream()
                .map(WalletMovementResponse::from)
                .toList());
    }

    @Operation(summary = "Wallet summary for a party",
            description = "Rollup across all credit instruments — total available credit, total wallet balance, total debt.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Summary returned"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping(value = "/summary", params = "partyId")
    ResponseEntity<WalletSummaryResponse> summary(@RequestParam UUID partyId) {
        return ResponseEntity.ok(WalletSummaryResponse.from(getWalletViewUseCase.listByPartyId(partyId)));
    }

    @Operation(summary = "Create a payment instruction")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Instruction created"),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "404", description = "Wallet not found"),
        @ApiResponse(responseCode = "409", description = "Duplicate pending instruction"),
        @ApiResponse(responseCode = "422", description = "Business rule violation")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{creditAccountId}/payment-instructions")
    ResponseEntity<PaymentInstructionResponse> createPaymentInstruction(
            @PathVariable UUID creditAccountId,
            @Valid @RequestBody CreatePaymentInstructionRequest req,
            Authentication auth) {
        var obligorPartyId = UUID.fromString(auth.getName());
        var cmd = new CreatePaymentInstructionCommand(
                creditAccountId, obligorPartyId,
                req.paymentMethod(), req.amount(), req.paymentType(), req.scheduledAt());
        var instruction = createInstructionUseCase.create(cmd);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(instruction.getInstructionId()).toUri();
        return ResponseEntity.created(location).body(PaymentInstructionResponse.from(instruction));
    }

    @Operation(summary = "Request a disposition (disbursement)")
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Disposition request accepted"),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "404", description = "Wallet not found"),
        @ApiResponse(responseCode = "422", description = "Insufficient credit or business rule violation")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{creditAccountId}/dispositions")
    ResponseEntity<Void> requestDisposition(
            @PathVariable UUID creditAccountId,
            @Valid @RequestBody RequestDispositionRequest req,
            Authentication auth) {
        var obligorPartyId = UUID.fromString(auth.getName());
        var cmd = new RequestDispositionCommand(
                creditAccountId, obligorPartyId,
                req.amount(), null,   // el tipo lo decide el producto (BK-13)
                req.beneficiaryPartyId(), req.payeeAccount(), req.termPeriods());
        requestDispositionUseCase.request(cmd);
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Withdraw from the wallet balance", description =
            "Sends money out of walletBalance (SPEI/CoDi) — spending/transferring what was already disposed, not repaying debt.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Withdrawal sent"),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "404", description = "Wallet not found"),
        @ApiResponse(responseCode = "422", description = "Insufficient wallet balance")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/{creditAccountId}/withdrawals")
    ResponseEntity<WalletWithdrawalResponse> withdraw(
            @PathVariable UUID creditAccountId,
            @Valid @RequestBody WithdrawFromWalletRequest req,
            Authentication auth) {
        var obligorPartyId = UUID.fromString(auth.getName());
        var cmd = new WithdrawFromWalletCommand(
                creditAccountId, obligorPartyId, req.method(), req.amount(), req.payeeAccount());
        var withdrawal = withdrawFromWalletUseCase.withdraw(cmd);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(withdrawal.getWithdrawalId()).toUri();
        return ResponseEntity.created(location).body(WalletWithdrawalResponse.from(withdrawal));
    }
}
