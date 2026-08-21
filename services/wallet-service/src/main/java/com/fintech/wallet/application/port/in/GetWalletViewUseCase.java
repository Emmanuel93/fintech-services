package com.fintech.wallet.application.port.in;

import com.fintech.wallet.domain.WalletView;
import java.util.List;
import java.util.UUID;

public interface GetWalletViewUseCase {
    WalletView getByCreditAccountId(UUID creditAccountId);
    /** All credit instruments (WalletView) for a party — the "general wallet" view. */
    List<WalletView> listByPartyId(UUID obligorPartyId);
}
