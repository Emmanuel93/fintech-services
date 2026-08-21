/**
 * D4 — Credit Product Catalog [Core]
 *
 * <p>Catálogo de definiciones de productos de crédito. Cada entrada define
 * los parámetros de un producto (tipo, tasas, plazos, línea de crédito, score
 * mínimo de aprobación, documentos requeridos y canales disponibles).
 *
 * <p>Origination consulta este catálogo para armar ofertas. La cartera
 * crediticia referenciará el {@code productDefinitionId} al activar una cuenta.
 *
 * <p>Schema DB: {@code credit_product}
 */
package com.fintech.creditproduct;
