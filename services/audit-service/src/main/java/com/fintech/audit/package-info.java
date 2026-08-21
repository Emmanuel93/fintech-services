/**
 * T3 — Audit &amp; Compliance
 *
 * <p>Log inmutable regulatorio. Suscriptor global de todos los eventos Kafka.
 * Append-only — nunca modifica estado de ningún otro dominio.
 * Retención: buró 5a, contratos 10a, estados 5a, AML 10a.
 *
 * <p>Schema DB: {@code audit}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.audit;
