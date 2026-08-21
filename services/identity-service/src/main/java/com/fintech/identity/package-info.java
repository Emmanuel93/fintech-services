/**
 * T1 — Identity &amp; Auth
 *
 * <p>Prerequisito síncrono para toda la plataforma.
 * Emite y valida JWT. No emite eventos Kafka propios.
 * Expone {@code GET /api/v1/auth/validate} para validación síncrona por otros módulos.
 *
 * <p>Schema DB: {@code identity}
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"shared"}
)
package com.fintech.identity;
