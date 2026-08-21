/**
 * D9 — Risk [Supporting]
 *
 * <p>Clasificación de riesgo crediticio vigente (etapa IFRS-9) y estimación preventiva de reservas
 * (EPR/ECL) por cuenta. <strong>Nunca modifica saldos ni ejecuta cobranza — solo clasifica.</strong>
 * 100% event-driven, sin ACL síncrona.
 *
 * <p>{@code ifrs9Stage} (STAGE_1/2/3) es la clasificación contable viva que se degrada con el
 * comportamiento de pago — distinta del {@code riskTier} de Scoring (foto estática al originar).
 * La provisión se calcula como {@code ead × expectedLossRate}, donde la tasa viene de una
 * {@code ProvisionPolicy} versionada por {@code (productType, bucket)} — tabla determinística y
 * auditable, sin componente estadístico (RC-07).
 *
 * <p>Transiciones de stage solo por el job nocturno ({@code RiskAssessmentJob}), nunca reactivas a
 * un evento aislado (RC-03, evita flapping intradía). Reestructura fuerza mínimo STAGE_2 durante el
 * período de cura (RC-04). {@code status=CLOSED} congela la reserva (RC-06).
 *
 * <p>Schema DB: {@code risk}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.risk;
