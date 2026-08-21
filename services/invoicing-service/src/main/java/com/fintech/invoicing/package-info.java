/**
 * Facturación (CFDI 4.0) [Transversal].
 *
 * <p>Servicio de facturación: consume {@code accounting.invoice-requested} (una solicitud
 * consolidada por party/período) y genera un registro de {@code Invoice} (CFDI) con su receptor y
 * conceptos. El receptor sale del {@code FiscalProfile} local (proyección de
 * {@code party.fiscal-profile-updated}); si el party aún no tiene perfil fiscal, se usa el RFC
 * genérico "público en general" (XAXX010101000).
 *
 * <p>El timbrado ante el PAC es un <strong>stub</strong> ({@code NoopPacAdapter}) por ahora — asigna
 * un folio fiscal simulado. El adaptador real de PAC se integra después sin tocar el dominio.
 *
 * <p>Schema DB: {@code invoicing}
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"shared"})
package com.fintech.invoicing;
