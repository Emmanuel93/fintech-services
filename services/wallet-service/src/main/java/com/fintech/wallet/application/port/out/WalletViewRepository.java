package com.fintech.wallet.application.port.out;

import com.fintech.wallet.domain.WalletView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletViewRepository {
    Optional<WalletView> findByCreditAccountId(UUID creditAccountId);
    List<WalletView> findByObligorPartyId(UUID obligorPartyId);
    WalletView save(WalletView walletView);

    // ── Actualizaciones atómicas por columna ────────────────────────────────
    // Evitan el lost-update entre listeners concurrentes (BalanceUpdated vs
    // DispositionCompleted/Withdrawal): walletBalance y las columnas de deuda se
    // escriben por separado, nunca con un save() de la entidad completa.

    /** Suma al walletBalance (disposición SELF_USE). Devuelve filas afectadas (0 = no existe). */
    int creditWalletBalance(UUID creditAccountId, BigDecimal amount);

    /** Resta del walletBalance sólo si hay saldo suficiente. 0 filas = saldo insuficiente o no existe. */
    int debitWalletBalanceIfEnough(UUID creditAccountId, BigDecimal amount);

    /** Actualiza SÓLO las columnas de deuda/estatus (nunca walletBalance). 0 filas = no existe. */
    int updateBalanceColumns(UUID creditAccountId, BigDecimal principalBalance,
                             BigDecimal accruedInterestBalance, BigDecimal penaltyBalance,
                             BigDecimal availableCredit, BigDecimal totalDebt,
                             String status, long balanceVersion);
}
