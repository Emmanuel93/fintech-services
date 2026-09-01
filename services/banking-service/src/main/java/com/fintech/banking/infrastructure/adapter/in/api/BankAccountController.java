package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.application.port.in.ManageBankAccountsUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-accounts")
class BankAccountController {

    private final ManageBankAccountsUseCase cuentas;

    BankAccountController(ManageBankAccountsUseCase cuentas) { this.cuentas = cuentas; }

    @PostMapping
    ResponseEntity<BankAccountResponse> alta(@Valid @RequestBody CreateBankAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BankAccountResponse.de(cuentas.alta(req.aComando())));
    }

    @GetMapping("/{id}")
    BankAccountResponse consultar(@PathVariable UUID id) {
        return BankAccountResponse.de(cuentas.consultar(id));
    }

    @GetMapping
    List<BankAccountResponse> listar(@RequestParam(required = false) UUID companyId) {
        return cuentas.listar(companyId).stream().map(BankAccountResponse::de).toList();
    }

    @PostMapping("/{id}/suspend")
    BankAccountResponse suspender(@PathVariable UUID id) {
        return BankAccountResponse.de(cuentas.suspender(id));
    }

    @PostMapping("/{id}/reactivate")
    BankAccountResponse reactivar(@PathVariable UUID id) {
        return BankAccountResponse.de(cuentas.reactivar(id));
    }
}
