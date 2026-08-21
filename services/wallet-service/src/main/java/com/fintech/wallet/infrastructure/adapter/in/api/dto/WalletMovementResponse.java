package com.fintech.wallet.infrastructure.adapter.in.api.dto;

import com.fintech.wallet.domain.WalletMovement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletMovementResponse(
        UUID movementId,
        UUID creditAccountId,
        String type,
        String direction,
        BigDecimal amount,
        String description,
        String status,
        String reference,
        Instant createdAt
) {
    public static WalletMovementResponse from(WalletMovement m) {
        return new WalletMovementResponse(
                m.getMovementId(), m.getCreditAccountId(), m.getType(), m.getDirection(),
                m.getAmount(), m.getDescription(), m.getStatus(), m.getReference(), m.getCreatedAt());
    }
}
