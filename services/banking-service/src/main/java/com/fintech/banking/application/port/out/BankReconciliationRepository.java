package com.fintech.banking.application.port.out;

import com.fintech.banking.domain.BankCloseSeal;
import com.fintech.banking.domain.BankMatch;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BankReconciliationRepository {

    BankStatementLine saveLine(BankStatementLine linea);

    Optional<BankStatementLine> findLineByExternalId(UUID bankAccountId, String externalId);

    List<BankStatementLine> findLinesByDate(UUID bankAccountId, LocalDate businessDate);

    BankMatch saveMatch(BankMatch cruce);

    SuspenseEntry saveSuspense(SuspenseEntry partida);

    /** Las partidas abiertas de una cuenta hasta una fecha. Son las que el sello tiene que sumar. */
    List<SuspenseEntry> findOpenSuspenseUpTo(UUID bankAccountId, LocalDate businessDate);

    BankCloseSeal saveSeal(BankCloseSeal sello);

    Optional<BankCloseSeal> findSeal(UUID bankAccountId, LocalDate businessDate, String periodType);
}
