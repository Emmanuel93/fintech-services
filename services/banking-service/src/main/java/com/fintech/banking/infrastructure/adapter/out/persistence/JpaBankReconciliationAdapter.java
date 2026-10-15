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

    private final SpringDataBankStatementLineRepository lines;
    private final SpringDataBankMatchRepository cruces;
    private final SpringDataSuspenseEntryRepository partidas;
    private final SpringDataBankCloseSealRepository seals;

    JpaBankReconciliationAdapter(SpringDataBankStatementLineRepository lines,
                                 SpringDataBankMatchRepository cruces,
                                 SpringDataSuspenseEntryRepository partidas,
                                 SpringDataBankCloseSealRepository seals) {
        this.lines   = lines;
        this.cruces   = cruces;
        this.partidas = partidas;
        this.seals   = seals;
    }

    @Override public BankStatementLine saveLine(BankStatementLine l) { return lines.save(l); }

    @Override public Optional<BankStatementLine> findLineByExternalId(UUID account, String externalId) {
        return lines.findByBankAccountIdAndExternalId(account, externalId);
    }

    @Override public List<BankStatementLine> findLinesByDate(UUID account, LocalDate date) {
        return lines.findByBankAccountIdAndBusinessDate(account, date);
    }

    @Override public BankMatch saveMatch(BankMatch m)         { return cruces.save(m); }
    @Override public SuspenseEntry saveSuspense(SuspenseEntry e) { return partidas.save(e); }

    @Override public List<SuspenseEntry> findOpenSuspenseUpTo(UUID account, LocalDate date) {
        return partidas.findByBankAccountIdAndStatusAndBusinessDateLessThanEqual(account, "OPEN", date);
    }

    @Override public BankCloseSeal saveSeal(BankCloseSeal s)  { return seals.save(s); }

    @Override public Optional<BankCloseSeal> findSeal(UUID account, LocalDate date, String period) {
        return seals.findByBankAccountIdAndBusinessDateAndPeriodType(account, date, period);
    }
}
