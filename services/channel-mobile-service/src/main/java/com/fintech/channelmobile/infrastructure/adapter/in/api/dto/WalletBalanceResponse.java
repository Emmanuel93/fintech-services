package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import java.math.BigDecimal;

/**
 * Shape que espera fa_wallet. wallet-service no expone el CLABE real (solo
 * `hasRegisteredClabe: boolean`) — `clabe` queda null hasta que el dominio lo exponga.
 */
public record WalletBalanceResponse(
        BigDecimal available,
        String accountNumber,
        String currency,
        String clabe
) {}
