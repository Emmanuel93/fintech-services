package com.fintech.accounting.application;

import java.math.BigDecimal;

/** Renglón de la balanza de comprobación (mayor): saldo agregado por cuenta en un período. */
public record TrialBalanceRow(
        String accountCode,
        String accountName,
        String accountType,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal balance   // debit - credit
) {}
