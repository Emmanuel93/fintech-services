package com.fintech.wallet.application.port.in;

import com.fintech.wallet.application.WithdrawFromWalletCommand;
import com.fintech.wallet.domain.WalletWithdrawal;

public interface WithdrawFromWalletUseCase {
    WalletWithdrawal withdraw(WithdrawFromWalletCommand command);
}
