package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.domain.BankAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataBankAccountRepository extends JpaRepository<BankAccount, UUID> {

    Optional<BankAccount> findByClabe(String clabe);

    List<BankAccount> findByCompanyId(UUID companyId);
}
