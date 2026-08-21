package com.fintech.stp.application.port.out;

import com.fintech.stp.domain.OrderingAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderingAccountRepository {

    Optional<OrderingAccount> findById(UUID orderingAccountId);

    /** La cuenta de la que sale el dinero de esta empresa. */
    Optional<OrderingAccount> findDefaultByCompanyId(UUID companyId);

    List<OrderingAccount> findByCompanyId(UUID companyId);

    OrderingAccount save(OrderingAccount account);

    /** Ver {@code StpCompanyKeyRepository#saveAndFlush}: hay un índice único parcial de por medio. */
    OrderingAccount saveAndFlush(OrderingAccount account);
}
