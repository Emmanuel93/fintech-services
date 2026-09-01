package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.domain.BankAccount;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaBankAccountAdapter implements BankAccountRepository {

    private final SpringDataBankAccountRepository jpa;

    JpaBankAccountAdapter(SpringDataBankAccountRepository jpa) { this.jpa = jpa; }

    @Override public BankAccount save(BankAccount cuenta)          { return jpa.save(cuenta); }
    @Override public Optional<BankAccount> findById(UUID id)       { return jpa.findById(id); }
    @Override public Optional<BankAccount> findByClabe(String c)   { return jpa.findByClabe(c); }
    @Override public List<BankAccount> findByCompany(UUID company) { return jpa.findByCompanyId(company); }
    @Override public List<BankAccount> findAll()                   { return jpa.findAll(); }
}
