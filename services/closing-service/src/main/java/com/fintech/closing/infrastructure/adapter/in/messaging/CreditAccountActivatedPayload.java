package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Proyección ACL del {@code CreditAccountActivatedEvent} de cartera
 * (tópico {@code credit-portfolio.credit-account-activated}).
 *
 * <p><b>`paymentFrequency` es el campo que justifica este consumidor.</b> Es la periodicidad que el
 * producto declara —semanal, quincenal o mensual— y es lo que decide cada cuánto corta la cuenta. El
 * cierre la guarda al activar y <b>no vuelve a preguntar</b>: consultar cartera dentro de la ventana
 * de cierre rompería su aislamiento, que es la razón de ser de {@code CutoffRule}.
 *
 * <p>Se declaran más campos de los que el perfil usa porque el payload los trae y
 * {@code @JsonIgnoreProperties} ya cubre lo que no se declara: tener el récord completo hace visible
 * qué hay disponible el día que haga falta otro dato, en vez de obligar a ir a leer el productor.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreditAccountActivatedPayload(
        String eventId,
        UUID creditAccountId,
        UUID contractId,
        UUID obligorPartyId,
        String productType,
        String productBehavior,
        BigDecimal nominalRate,
        BigDecimal moratoriumRate,
        BigDecimal principalBalance,
        BigDecimal creditLimit,
        String riskTier,
        Instant activatedAt,
        String originUnitCode,
        /** La periodicidad del producto: `WEEKLY` · `BIWEEKLY` · `SEMI_MONTHLY` · `MONTHLY`. */
        String paymentFrequency,
        /** The product's billing cycle pinned to the account; null for products that never declared one. */
        BillingCyclePayload billingCycle,
        /** Nulo en un revolvente: una línea no tiene plazo, lo tiene cada disposición. */
        Integer termPeriods) {

    /** What the product declared about cutting and paying, as the portfolio republishes it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BillingCyclePayload(String cutoffRule, Integer cutoffDay, Integer paymentDueOffsetDays,
                                      Integer graceDays, BigDecimal minimumPaymentRate, String nonBusinessDayShift,
                                      /** SEMI_MONTHLY: the two cutoff days, and the day each statement is paid by. */
                                      List<Integer> cutoffDays, List<Integer> paymentDueDays) {}
}
