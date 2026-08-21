package com.fintech.wallet.application.port.out;

import com.fintech.wallet.domain.PaymentInstruction;
import com.fintech.wallet.domain.WalletView;
import com.fintech.wallet.domain.WalletWithdrawal;
import java.math.BigDecimal;
import java.util.UUID;

public interface WalletEventPublisher {
    void publishPaymentInstructionCreated(PaymentInstruction instruction);
    /** @return the generated dispositionRequestId — the idempotency key credit-portfolio uses to dedupe. */
    UUID publishDispositionRequested(UUID creditAccountId, UUID obligorPartyId,
                                      BigDecimal amount, String dispositionType,
                                      UUID beneficiaryPartyId, String payeeAccount,
                                      Integer termPeriods);
    void publishWalletSnapshotUpdated(WalletView walletView);
    void publishWithdrawalCompleted(WalletWithdrawal withdrawal);
}
