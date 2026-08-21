package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.LedgerAccountRepository;
import com.fintech.accounting.domain.LedgerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaLedgerAccountRepository extends JpaRepository<LedgerAccount, String>, LedgerAccountRepository {
}
