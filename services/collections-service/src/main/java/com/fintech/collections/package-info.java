/**
 * D8 — Collections [Supporting]
 *
 * <p>Cobranza temprana (pre-vencimiento), gestión de mora, acuerdos de cobranza
 * (reestructura / quita parcial), quebranto (quita total) y reporte a buró.
 * <strong>Nunca modifica saldos directamente.</strong>
 * Write-off en dos pasos: {@code WriteOffRequested} (intención) → {@code WriteOffExecuted} (autorización).
 * {@code CollectionAgreement} en tres pasos: {@code PROPOSED} → {@code ACCEPTED} (deudor) → {@code EXECUTED} (autorización).
 * Collections cobra siempre al {@code obligorPartyId} — nunca al beneficiario.
 * CONDUSEF: máx 3 intentos contacto/día, horario 08:00–20:00.
 *
 * <p>Schema DB: {@code collections}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.collections;
