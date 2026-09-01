package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.domain.BankAccount;

import java.util.UUID;

/**
 * La CLABE sale <b>enmascarada</b>. Quien opera el backoffice no necesita el número completo para
 * decidir, y una pantalla que lo muestra es una pantalla que se fotografía.
 */
public record BankAccountResponse(UUID bankAccountId,
                                  UUID companyId,
                                  String institutionCode,
                                  String institutionName,
                                  String clabe,
                                  String holderName,
                                  String currency,
                                  String ledgerAccount,
                                  String suspenseCreditAccount,
                                  String suspenseDebitAccount,
                                  String status) {

    static BankAccountResponse de(BankAccount c) {
        return new BankAccountResponse(
                c.getId(), c.getCompanyId(), c.getInstitutionCode(), c.getInstitutionName(),
                c.getClabeEnmascarada(), c.getHolderName(), c.getCurrency(),
                c.getLedgerAccount(), c.getSuspenseCreditAccount(), c.getSuspenseDebitAccount(),
                c.getStatus().name());
    }
}
