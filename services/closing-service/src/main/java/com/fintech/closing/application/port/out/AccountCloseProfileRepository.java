package com.fintech.closing.application.port.out;

import com.fintech.closing.domain.AccountCloseProfile;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountCloseProfileRepository {
    Optional<AccountCloseProfile> findById(UUID creditAccountId);
    AccountCloseProfile save(AccountCloseProfile profile);
    /** Las cuentas vivas que entran a una corrida de cierre. */
    List<AccountCloseProfile> findActiveForClosing(LocalDate businessDate, int limit);
    long countActive();
}
