/**
 * D3 — Origination [Core]
 *
 * <p>Orquestador del ciclo de aprobación.
 * Inicia en {@code ApplicationStarted}, termina en {@code ContractSigned + CreditProductCreationRequested}.
 * Selecciona flow AUTOMATIC / MANUAL / COMMITTEE según score y condiciones.
 * Contrato firmado es <strong>inmutable</strong> — reestructuras van por D4.
 *
 * <p>Schema DB: {@code origination}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.origination;
