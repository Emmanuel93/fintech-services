package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.AccountingPeriod;

import java.util.List;
import java.util.Optional;

public interface AccountingPeriodRepository {
    Optional<AccountingPeriod> findById(String period);
    List<AccountingPeriod> findAllOrdered();
    /** El primer período abierto igual o posterior al dado. Adonde va una partida extemporánea. */
    Optional<AccountingPeriod> firstOpenFrom(String period);
    AccountingPeriod save(AccountingPeriod period);
}
