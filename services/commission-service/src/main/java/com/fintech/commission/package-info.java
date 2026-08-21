/**
 * T6 — Commission [Transversal]
 *
 * <p>Motor de acumulación y liquidación de comisiones para la red comercial (promotores,
 * distribuidores B2B2C, gestores de cobranza). <strong>T6 acumula, T4 paga</strong> — nunca modifica
 * saldos crediticios ni ejecuta el SPEI, solo decide cuánto se le debe a cada beneficiario.
 *
 * <p><strong>Modelo del distribuidor B2B2C ({@code DISTRIBUTOR_INTEREST_SHARE}) — corregido
 * 2026-07-14:</strong> la comisión va <strong>contra el pago</strong>, nunca por adelantado contra
 * la colocación. Es un % del <strong>interés efectivamente cobrado</strong> (nunca capital),
 * devengado <strong>plazo a plazo</strong> mientras el cliente paga, configurable por producto
 * (versionado, {@code CommissionPolicy}). Si el cliente no paga, el distribuidor no gana — el
 * incentivo se autocorrige y comparte el riesgo de recuperación con la plataforma.
 *
 * <p>El interés cobrado en cada pago se deriva del <strong>delta</strong> de
 * {@code credit-portfolio.balance-updated} (baja de {@code accruedInterestBalance} en un
 * {@code PAYMENT_APPLIED}) vía un {@code AccountBalanceShadow} local — mismo patrón que Accounting
 * (T4) — porque {@code PaymentApplied} no trae el split interés/capital.
 *
 * <p>Schema DB: {@code commission}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.commission;
