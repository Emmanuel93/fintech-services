package com.fintech.banking.application.port.in;

import com.fintech.banking.domain.BankAccount;

import java.util.List;
import java.util.UUID;

/** Alta y consulta de las cuentas propias. Es el catálogo del que el ruteo elige. */
public interface ManageBankAccountsUseCase {

    /**
     * @param companyId            nulo = cuenta de la operación, no de una empresa concreta
     * @param suspenseCreditAccount puente de abonos sin identificar; por defecto {@code 2109}
     * @param suspenseDebitAccount  puente de cargos sin aclarar; por defecto {@code 1109}
     */
    record AltaDeCuenta(UUID companyId,
                        String institutionCode,
                        String institutionName,
                        String clabe,
                        String holderName,
                        String taxId,
                        String currency,
                        String ledgerAccount,
                        String suspenseCreditAccount,
                        String suspenseDebitAccount,
                        String providerClientRef) {}

    BankAccount alta(AltaDeCuenta alta);

    BankAccount consultar(UUID id);

    List<BankAccount> listar(UUID companyId);

    BankAccount suspender(UUID id);

    BankAccount reactivar(UUID id);
}
