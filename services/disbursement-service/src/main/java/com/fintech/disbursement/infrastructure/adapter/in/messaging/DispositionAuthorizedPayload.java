package com.fintech.disbursement.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Forma de {@code credit-portfolio.disposition-authorized}: una disposición sobre una línea ya
 * activa, autorizada y pendiente de pago. Es el camino de wallet, distinto del de activación.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DispositionAuthorizedPayload(
        UUID dispositionId,
        UUID creditAccountId,
        UUID companyId,
        String dispositionType,
        BigDecimal amount,
        String currency,
        String beneficiaryName,
        String beneficiaryAccount,
        String beneficiaryAccountType,
        String beneficiaryTaxId,
        Integer beneficiaryInstitution,
        Long numericReference,
        String concept,
        Instant occurredOn
) {}
