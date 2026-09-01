package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.application.port.in.ManageBankAccountsUseCase.AltaDeCuenta;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record CreateBankAccountRequest(UUID companyId,
                                       String institutionCode,
                                       @NotBlank String institutionName,
                                       @NotBlank String clabe,
                                       @NotBlank String holderName,
                                       String taxId,
                                       String currency,
                                       String ledgerAccount,
                                       String suspenseCreditAccount,
                                       String suspenseDebitAccount,
                                       String providerClientRef) {

    AltaDeCuenta aComando() {
        return new AltaDeCuenta(companyId, institutionCode, institutionName, clabe, holderName,
                taxId, currency, ledgerAccount, suspenseCreditAccount, suspenseDebitAccount,
                providerClientRef);
    }
}
