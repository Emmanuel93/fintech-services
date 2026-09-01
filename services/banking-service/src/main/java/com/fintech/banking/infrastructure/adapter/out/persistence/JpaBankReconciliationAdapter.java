package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.application.port.out.BankReconciliationRepository;
import com.fintech.banking.domain.BankCloseSeal;
import com.fintech.banking.domain.BankMatch;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaBankReconciliationAdapter implements BankReconciliationRepository {

    private final SpringDataBankStatementLineRepository lineas;
    private final SpringDataBankMatchRepository cruces;
    private final SpringDataSuspenseEntryRepository partidas;
    private final SpringDataBankCloseSealRepository sellos;

    JpaBankReconciliationAdapter(SpringDataBankStatementLineRepository lineas,
                                 SpringDataBankMatchRepository cruces,
                                 SpringDataSuspenseEntryRepository partidas,
                                 SpringDataBankCloseSealRepository sellos) {
        this.lineas   = lineas;
        this.cruces   = cruces;
        this.partidas = partidas;
        this.sellos   = sellos;
    }

    @Override public BankStatementLine saveLine(BankStatementLine l) { return lineas.save(l); }

    @Override public Optional<BankStatementLine> findLineByExternalId(UUID cuenta, String externalId) {
        return lineas.findByBankAccountIdAndExternalId(cuenta, externalId);
    }

    @Override public List<BankStatementLine> findLinesByDate(UUID cuenta, LocalDate fecha) {
        return lineas.findByBankAccountIdAndBusinessDate(cuenta, fecha);
    }

    @Override public BankMatch saveMatch(BankMatch m)         { return cruces.save(m); }
    @Override public SuspenseEntry saveSuspense(SuspenseEntry e) { return partidas.save(e); }

    @Override public List<SuspenseEntry> findOpenSuspenseUpTo(UUID cuenta, LocalDate fecha) {
        return partidas.findByBankAccountIdAndStatusAndBusinessDateLessThanEqual(cuenta, "OPEN", fecha);
    }

    @Override public BankCloseSeal saveSeal(BankCloseSeal s)  { return sellos.save(s); }

    @Override public Optional<BankCloseSeal> findSeal(UUID cuenta, LocalDate fecha, String periodo) {
        return sellos.findByBankAccountIdAndBusinessDateAndPeriodType(cuenta, fecha, periodo);
    }
}
