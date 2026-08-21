package com.fintech.payments.infrastructure.adapter.in.api.dto;

import com.fintech.payments.domain.AccountBalanceSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceSnapshotResponse(
        UUID creditAccountId,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        BigDecimal availableCredit,
        BigDecimal totalDebt,
        BigDecimal creditLimit,
        long balanceVersion,
        String accountStatus,
        Instant snapshotAt
) {
    public static BalanceSnapshotResponse from(AccountBalanceSnapshot s) {
        return new BalanceSnapshotResponse(
                s.getCreditAccountId(), s.getPrincipalBalance(), s.getAccruedInterestBalance(),
                s.getPenaltyBalance(), s.getAvailableCredit(), s.getTotalDebt(),
                s.getCreditLimit(), s.getBalanceVersion(), s.getAccountStatus(), s.getSnapshotAt());
    }
}
