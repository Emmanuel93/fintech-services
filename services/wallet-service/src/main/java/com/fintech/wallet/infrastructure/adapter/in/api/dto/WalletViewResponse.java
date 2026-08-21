package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.WalletView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record WalletViewResponse(
        UUID walletId,
        UUID creditAccountId,
        UUID obligorPartyId,
        String productType,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        BigDecimal totalDebt,
        BigDecimal availableCredit,
        BigDecimal walletBalance,
        BigDecimal minimumPayment,
        LocalDate paymentDueDate,
        BigDecimal nextInstallmentAmount,
        String status,
        Instant lastUpdatedAt,
        boolean hasRegisteredClabe
) {
    public static WalletViewResponse from(WalletView v) {
        return new WalletViewResponse(
                v.getWalletId(), v.getCreditAccountId(), v.getObligorPartyId(),
                v.getProductType(), v.getPrincipalBalance(), v.getAccruedInterestBalance(),
                v.getPenaltyBalance(), v.getTotalDebt(), v.getAvailableCredit(), v.getWalletBalance(),
                v.getMinimumPayment(), v.getPaymentDueDate(), v.getNextInstallmentAmount(),
                v.getStatus(), v.getLastUpdatedAt(),
                v.getRegisteredClabe() != null && !v.getRegisteredClabe().isBlank());
    }
}
