package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.Disposition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispositionRepository {
    Disposition save(Disposition disposition);
    Optional<Disposition> findById(UUID dispositionId);
    List<Disposition> findByCreditAccountId(UUID creditAccountId);

    /** Disposiciones de varias cuentas — evita el N+1 al armar el plan de una página de líneas. */
    List<Disposition> findByCreditAccountIdIn(java.util.Collection<UUID> creditAccountIds);
}
