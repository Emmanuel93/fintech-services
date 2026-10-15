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

    private final ManageBankAccountsUseCase accounts;

    BankAccountController(ManageBankAccountsUseCase accounts) { this.accounts = accounts; }

    @PostMapping
    ResponseEntity<BankAccountResponse> register(@Valid @RequestBody CreateBankAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(BankAccountResponse.from(accounts.register(req.aComando())));
    }

    @GetMapping("/{id}")
    BankAccountResponse query(@PathVariable UUID id) {
        return BankAccountResponse.from(accounts.query(id));
    }

    @GetMapping
    List<BankAccountResponse> list(@RequestParam(required = false) UUID companyId) {
        return accounts.list(companyId).stream().map(BankAccountResponse::from).toList();
    }

    @PostMapping("/{id}/suspend")
    BankAccountResponse suspender(@PathVariable UUID id) {
        return BankAccountResponse.from(accounts.suspender(id));
    }

    @PostMapping("/{id}/reactivate")
    BankAccountResponse reactivate(@PathVariable UUID id) {
        return BankAccountResponse.from(accounts.reactivate(id));
    }
}
