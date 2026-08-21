package com.fintech.wallet.application;

import com.fintech.wallet.domain.PaymentMethod;
import java.math.BigDecimal;
import java.util.UUID;

public record WithdrawFromWalletCommand(
        UUID creditAccountId,
        UUID obligorPartyId,
        PaymentMethod method,
        BigDecimal amount,
        String payeeAccount
) {}
