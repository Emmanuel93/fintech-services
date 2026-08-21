/**
 * D1 — Channels [Supporting]
 *
 * <p>Punto de entrada. Captación y routing a Origination.
 * <strong>Sin lógica de decisión crediticia.</strong>
 * Valida sesión con T1 (REST síncrono). Publica {@code ApplicationStarted}.
 *
 * <p>Tipos de canal: DIGITAL_APP, WEB_PORTAL, BRANCH, API_PARTNER.
 * Schema DB: {@code channels}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.channels;
