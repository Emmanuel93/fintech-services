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
        /**
         * Con qué clave resolver la empresa cuando {@code companyId} viene nulo (DB-07).
         *
         * <p>Cartera manda la unidad de origen de la cuenta. Sin esto, toda disposición moría con
         * {@code UNRESOLVED_COMPANY} antes de llegar al proveedor.
         */
        String sourceCompanyKey,
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
        /* Which attempt of this disposition's payment (1 when absent): a failed one is asked again as a new order. */
        Integer attempt,
        /* The earliest moment this attempt may leave: the portfolio's wait between attempts. */
        Instant notBefore,
        Instant occurredOn
) {}
