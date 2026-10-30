package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.UUID;

/** Proyección ACL de {@code credit-portfolio.balance-updated}: los saldos con los que se sella. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BalanceUpdatedPayload(
        String eventId,
        UUID creditAccountId,
        BigDecimal principalBalance,
        BigDecimal accruedInterestBalance,
        BigDecimal penaltyBalance,
        BigDecimal totalDebt,
        String accountStatus,
        /**
         * La versión del saldo. <b>Es lo que evita que un evento viejo pise uno nuevo</b>: Kafka
         * garantiza orden por partición, no entre reentregas, y el perfil descarta lo atrasado.
         */
        long balanceVersion) {}
