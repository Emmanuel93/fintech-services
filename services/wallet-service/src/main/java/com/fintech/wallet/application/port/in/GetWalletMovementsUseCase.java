package com.fintech.wallet.application.port.in;

import com.fintech.wallet.domain.WalletMovement;

import java.util.List;
import java.util.UUID;

public interface GetWalletMovementsUseCase {
    List<WalletMovement> getMovementsByCreditAccountId(UUID creditAccountId);
}
