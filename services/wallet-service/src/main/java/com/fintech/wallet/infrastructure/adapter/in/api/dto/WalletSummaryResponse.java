package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.WalletView;

import java.math.BigDecimal;
import java.util.List;

/**
 * "General wallet" rollup for a party — one row per credit instrument (productType),
 * plus totals across all of them. Computed on read, not stored — avoids a second
 * source of truth for numbers WalletView already holds per instrument.
 */
public record WalletSummaryResponse(
        BigDecimal totalAvailableCredit,
        BigDecimal totalWalletBalance,
        BigDecimal totalDebt,
        List<WalletViewResponse> instruments
) {
    public static WalletSummaryResponse from(List<WalletView> views) {
        BigDecimal totalAvailableCredit = views.stream()
                .map(WalletView::getAvailableCredit)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalWalletBalance = views.stream()
                .map(WalletView::getWalletBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDebt = views.stream()
                .map(WalletView::getTotalDebt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new WalletSummaryResponse(
                totalAvailableCredit, totalWalletBalance, totalDebt,
                views.stream().map(WalletViewResponse::from).toList());
    }
}
