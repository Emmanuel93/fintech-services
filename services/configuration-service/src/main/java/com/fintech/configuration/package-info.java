/**
 * T5 — Configuration
 *
 * <p>Parámetros de negocio versionados con ciclo maker-checker.
 * Todos los módulos hacen pull de parámetros. Publica {@code ConfigurationUpdated}
 * para invalidar caches cuando un parámetro entra en vigencia.
 *
 * <p>Schema DB: {@code configuration}
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.configuration;
