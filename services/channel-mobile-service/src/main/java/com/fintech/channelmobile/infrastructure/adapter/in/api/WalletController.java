package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.WalletBalanceResponse;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.WalletTransferRequest;
import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.WalletClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * fa_wallet — saldo, movimientos y transferencia SPEI. wallet-service solo conoce
 * creditAccountId; aquí resolvemos la cuenta principal del party autenticado
 * (X-User-Id, inyectado por el gateway) contra credit-portfolio antes de llamarlo.
 */
@RestController
@Tag(name = "Wallet", description = "Saldo y movimientos del wallet asociado al crédito")
@SecurityRequirement(name = "bearerAuth")
class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final CreditPortfolioClient creditPortfolioClient;
    private final WalletClient walletClient;

    WalletController(CreditPortfolioClient creditPortfolioClient, WalletClient walletClient) {
        this.creditPortfolioClient = creditPortfolioClient;
        this.walletClient = walletClient;
    }

    @Operation(summary = "Saldo del wallet del usuario autenticado")
    @GetMapping("/wallet/balance")
    ResponseEntity<WalletBalanceResponse> balance(HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID partyId = UUID.fromString(userId);
        List<CreditPortfolioClient.CreditAccountResponse> accounts =
                creditPortfolioClient.getAccountsByPartyId(partyId, userId);
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El usuario no tiene cuentas de crédito activas");
        }
        CreditPortfolioClient.CreditAccountResponse account = accounts.get(0);
        UUID creditAccountId = account.creditAccountId();
        WalletClient.WalletViewResponse view = walletClient.getWalletView(creditAccountId, userId);
        // CLABE de depósito: la que el cliente registró al firmar (guardada en la cuenta).
        return ResponseEntity.ok(new WalletBalanceResponse(
                view.walletBalance(), creditAccountId.toString(), "MXN", account.clabeAccount()));
    }

    @Operation(summary = "Movimientos del wallet (historial)",
            description = "Ledger de wallet-service: disposiciones (crédito), retiros y pagos (débito). "
                    + "Se mapea al shape que espera la app; sin cuenta activa devuelve lista vacía.")
    @GetMapping("/wallet/transactions")
    ResponseEntity<Map<String, Object>> transactions(HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID partyId = UUID.fromString(userId);
        List<CreditPortfolioClient.CreditAccountResponse> accounts =
                creditPortfolioClient.getAccountsByPartyId(partyId, userId);
        if (accounts.isEmpty()) {
            return ResponseEntity.ok(Map.of("transactions", List.of()));
        }
        UUID creditAccountId = accounts.get(0).creditAccountId();
        List<Map<String, Object>> txns = walletClient.getMovements(creditAccountId, userId).stream()
                .map(WalletController::toAppTransaction)
                .toList();
        return ResponseEntity.ok(Map.of("transactions", txns));
    }

    /** Mapea un movimiento del ledger de wallet-service al shape que consume fa_wallet (TransactionDto). */
    private static Map<String, Object> toAppTransaction(WalletClient.MovementResponse m) {
        Map<String, Object> t = new java.util.HashMap<>();
        t.put("id", m.movementId().toString());
        t.put("type", "CREDIT".equals(m.direction()) ? "credit" : "debit");
        t.put("category", switch (m.type()) {
            case "DISPOSITION" -> "disposicion";
            case "WITHDRAWAL"  -> "transferOut";
            case "PAYMENT"     -> "payment";
            default             -> "other";
        });
        t.put("description", m.description());
        t.put("amount", m.amount());
        t.put("date", m.createdAt() != null ? m.createdAt().toString() : null);
        t.put("reference", m.reference());
        return t;
    }

    @Operation(summary = "Transferencia SPEI desde el wallet")
    @PostMapping("/wallet/transfer")
    ResponseEntity<Map<String, Object>> transfer(@Valid @RequestBody WalletTransferRequest request,
                                                  HttpServletRequest httpRequest) {
        String userId = httpRequest.getHeader("X-User-Id");
        UUID creditAccountId = resolvePrimaryCreditAccountId(userId);
        log.info("POST /wallet/transfer creditAccountId={} amount={}", creditAccountId, request.amount());
        // Nota: wallet-service no tiene campo `concept` en WithdrawFromWalletRequest — se pierde
        // hasta que el dominio lo soporte. payeeAccount hoy es el recipientPhone (no una CLABE real).
        walletClient.withdraw(creditAccountId, userId, "SPEI",
                BigDecimal.valueOf(request.amount()), request.recipientPhone());
        return ResponseEntity.ok(Map.of("success", true));
    }

    private UUID resolvePrimaryCreditAccountId(String userId) {
        UUID partyId = UUID.fromString(userId);
        List<CreditPortfolioClient.CreditAccountResponse> accounts =
                creditPortfolioClient.getAccountsByPartyId(partyId, userId);
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El usuario no tiene cuentas de crédito activas");
        }
        return accounts.get(0).creditAccountId();
    }
}
