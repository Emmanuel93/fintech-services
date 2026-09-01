package com.fintech.origination.domain;

/**
 * Credit product the prospect chooses in a {@link CreditApplication}.
 *
 * <p>Replaces the former {@code ProductTypeIntent} that lived on the Prospect.
 * Per ADR-001 the product is selected when a credit application is started,
 * not at person onboarding. This value drives the scoring policy selection
 * ({@code (prospectType, productType)}) and, downstream, the credit-product
 * catalog and credit-portfolio behaviour.
 */
public enum ProductType {
    PERSONAL_LOAN,
    REVOLVING_LINE,
    /**
     * Faltaba, y no era una decisión: era una omisión.
     *
     * <p>El catálogo tiene {@code CC-IND-STD-V1} activo desde su seed y {@code scoring} tiene su
     * política de riesgo lista —{@code CREDIT_CARD · INDIVIDUAL · activa}—. Sólo este enum no lo
     * aceptaba, así que <b>una tarjeta de crédito no se podía originar</b>: la solicitud rebotaba
     * con un 400 de deserialización antes de llegar a ninguna regla de negocio.
     *
     * <p>Es la segunda barrera, independiente de la del sembrador. Por eso la demo no tenía ni una
     * tarjeta viva: aunque el sembrador la eligiera, el alta la rechazaba.
     */
    CREDIT_CARD,
    /** Mismo caso: producto en catálogo y política de scoring, ausente sólo aquí. */
    MICRO_LOAN,
    PAYROLL_LOAN,
    GROUP_LOAN,
    DISTRIBUTOR_LINE,
    // B2B (empresa) — el comportamiento (INSTALLMENT/REVOLVING) lo define el catálogo, no este enum.
    SME_LOAN,
    BUSINESS_REVOLVING_LINE
}
