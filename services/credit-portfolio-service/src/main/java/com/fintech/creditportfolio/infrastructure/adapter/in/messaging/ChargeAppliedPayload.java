package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inbound from charges-service (topic {@code charges.charge-applied}).
 * chargeType ∈ ORDINARY_INTEREST | MORATORIUM_INTEREST | OPENING_FEE | ADMIN_FEE
 *            | PREPAYMENT_FEE | INSURANCE_PREMIUM.
 * ORDINARY_INTEREST hits accruedInterestBalance; everything else hits penaltyBalance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChargeAppliedPayload(
        String eventId,
        UUID creditAccountId,
        String chargeType,
        BigDecimal totalAmount,
        /**
         * El día al que pertenece el cargo; nulo en los productores que todavía no lo mandan.
         *
         * <p>Se propaga al {@code balance-updated} para que contabilidad asiente la póliza en el
         * período del hecho y no en el de su procesamiento.
         */
        LocalDate effectiveDate
) {}
