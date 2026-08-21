/**
 * D2 — Scoring [Core]
 *
 * <p>Motor de evaluación de riesgo. Consume {@code ScoreRequested}.
 * Emite {@code DecisionPolicy} con condiciones — <strong>no aprueba créditos</strong>.
 * La aprobación es responsabilidad de Origination (D3).
 *
 * <p>Modelo seleccionado por {@code (partyType, productType)}.
 * Score reuse si {@code validUntil} futuro y modelo ACTIVE.
 * Schema DB: {@code scoring}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.scoring;
