package com.fintech.charges.infrastructure.adapter.in.api.dto;

import com.fintech.charges.domain.AccountBalanceSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BalanceSnapshotResponse(
        UUID creditAccountId,
        UUID obligorPartyId,
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
                s.getCreditAccountId(), s.getObligorPartyId(),
                s.getPrincipalBalance(), s.getAccruedInterestBalance(),
                s.getPenaltyBalance(), s.getAvailableCredit(),
                s.getTotalDebt(), s.getCreditLimit(),
                s.getBalanceVersion(), s.getAccountStatus(), s.getSnapshotAt());
    }
}
