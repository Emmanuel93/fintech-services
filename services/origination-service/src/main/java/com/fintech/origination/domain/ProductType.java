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
    PAYROLL_LOAN,
    GROUP_LOAN,
    DISTRIBUTOR_LINE,
    // B2B (empresa) — el comportamiento (INSTALLMENT/REVOLVING) lo define el catálogo, no este enum.
    SME_LOAN,
    BUSINESS_REVOLVING_LINE
}
