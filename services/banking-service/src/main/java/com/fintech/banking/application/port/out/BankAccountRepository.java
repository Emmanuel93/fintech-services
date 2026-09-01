package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.BankAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankAccountRepository {

    BankAccount save(BankAccount cuenta);

    Optional<BankAccount> findById(UUID id);

    Optional<BankAccount> findByClabe(String clabe);

    /** Todas las cuentas de una empresa; {@code null} trae las que no están atadas a ninguna. */
    List<BankAccount> findByCompany(UUID companyId);

    List<BankAccount> findAll();
}
