/**
 * D0 — Party [Core Root]
 *
 * <p>Sujeto del crédito — raíz de todo. Todo crédito referencia un {@code partyId}.
 * {@code partyType} es <strong>inmutable post-creación</strong>.
 * {@code BLACKLISTED} bloquea toda nueva originación de forma inmediata.
 *
 * <p>Creado automáticamente cuando scoring emite {@code ScoringApprovedEvent}
 * (decisión {@code AUTO_APPROVED} — riesgo BAJO).
 *
 * <p>Schema DB: {@code party}
 */
package com.fintech.party;
