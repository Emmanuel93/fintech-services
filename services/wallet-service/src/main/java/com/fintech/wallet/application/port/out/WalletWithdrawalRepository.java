package com.fintech.wallet.application.port.out;

import com.fintech.wallet.domain.WalletWithdrawal;
import java.util.List;
import java.util.UUID;

public interface WalletWithdrawalRepository {
    List<WalletWithdrawal> findByCreditAccountId(UUID creditAccountId);
    WalletWithdrawal save(WalletWithdrawal withdrawal);
}
