package com.fintech.accounting.application.port.in;

import com.fintech.accounting.application.TrialBalanceRow;
import com.fintech.accounting.domain.JournalEntry;

import java.util.List;
import java.util.UUID;

public interface GetLedgerUseCase {
    /** Auxiliar: asientos de un crédito. */
    List<JournalEntry> journalByAccount(UUID creditAccountId);
    /** Auxiliar: asientos de un party (todas sus cuentas). */
    List<JournalEntry> journalByParty(UUID obligorPartyId);
    /** Mayor / balanza de comprobación: agregado por cuenta contable en un período (YYYYMM). */
    List<TrialBalanceRow> trialBalance(String period);
}
