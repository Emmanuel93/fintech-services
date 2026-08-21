package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.WalletWithdrawal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletWithdrawalResponse(
        UUID withdrawalId,
        UUID creditAccountId,
        String method,
        BigDecimal amount,
        String payeeAccount,
        String status,
        String externalRef,
        Instant createdAt
) {
    public static WalletWithdrawalResponse from(WalletWithdrawal w) {
        return new WalletWithdrawalResponse(
                w.getWithdrawalId(), w.getCreditAccountId(), w.getMethod(),
                w.getAmount(), w.getPayeeAccount(), w.getStatus(), w.getExternalRef(), w.getCreatedAt());
    }
}
