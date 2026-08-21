package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.LedgerAccount;

import java.util.List;

public interface LedgerAccountRepository {
    List<LedgerAccount> findAll();
}
