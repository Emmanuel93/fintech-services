package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Forma de {@code wallet.withdrawal-completed}: el cliente retira saldo a favor a una cuenta suya.
 *
 * <p>No tiene nada que ver con crédito, y por eso está: demuestra que el mismo núcleo sirve a
 * orígenes que no comparten dominio.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletWithdrawalCompletedPayload(
        UUID withdrawalId,
        UUID walletId,
        UUID companyId,
        BigDecimal amount,
        String currency,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryAccountType,
        String beneficiaryTaxId,
        Integer beneficiaryInstitution,
        String concept,
        Instant occurredOn
) {}
