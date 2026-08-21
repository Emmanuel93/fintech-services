package com.fintech.origination.domain;

import java.util.Set;

/**
 * Audiencia comercial del producto — uno de los tres ejes por los que el
 * backoffice filtra la bandeja de solicitudes.
 *
 * <ul>
 *   <li><b>B2C</b>: crédito de uso propio del cliente final.</li>
 *   <li><b>B2B2C</b>: la línea del distribuidor ({@code DISTRIBUTOR_LINE}) — el
 *       distribuidor recibe crédito para colocarlo entre sus clientes.</li>
 *   <li><b>B2B</b>: crédito de uso propio de una empresa.</li>
 * </ul>
 *
 * <p>Hoy la audiencia se <b>deriva</b> del {@link ProductType} con este mapeo.
 * Hacerlo editable desde backoffice (mapeo configurable audiencia↔producto) es
 * el follow-up de Task 4; este enum es el punto de extensión.
 */
public enum TargetAudience {

    B2C(Set.of(ProductType.PERSONAL_LOAN, ProductType.REVOLVING_LINE,
            ProductType.PAYROLL_LOAN, ProductType.GROUP_LOAN)),
    B2B2C(Set.of(ProductType.DISTRIBUTOR_LINE)),
    B2B(Set.of(ProductType.SME_LOAN, ProductType.BUSINESS_REVOLVING_LINE));

    private final Set<ProductType> productTypes;

    TargetAudience(Set<ProductType> productTypes) {
        this.productTypes = productTypes;
    }

    /** Los tipos de producto que caen en esta audiencia (para traducir el filtro a SQL). */
    public Set<ProductType> productTypes() {
        return productTypes;
    }

    /** La audiencia de un producto; por defecto B2C si no está mapeado explícitamente. */
    public static TargetAudience of(ProductType productType) {
        for (TargetAudience audience : values()) {
            if (audience.productTypes.contains(productType)) {
                return audience;
            }
        }
        return B2C;
    }
}
