package com.fintech.wallet.application.port.out;

import com.fintech.wallet.domain.WalletMovement;

import java.util.List;
import java.util.UUID;

public interface WalletMovementRepository {
    WalletMovement save(WalletMovement movement);
    List<WalletMovement> findByCreditAccountId(UUID creditAccountId);
}
