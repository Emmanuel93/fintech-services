package com.fintech.creditproduct.domain;

public enum ProductType {

    // ── REVOLVING — B2C ───────────────────────────────────────────────────────
    CREDIT_CARD             (ProductBehavior.REVOLVING),
    REVOLVING_LINE          (ProductBehavior.REVOLVING),

    // ── REVOLVING — B2B2C ────────────────────────────────────────────────────
    DISTRIBUTOR_LINE        (ProductBehavior.REVOLVING),

    // ── REVOLVING — B2B ──────────────────────────────────────────────────────
    BUSINESS_REVOLVING_LINE (ProductBehavior.REVOLVING),

    // ── INSTALLMENT — B2C ────────────────────────────────────────────────────
    PERSONAL_LOAN           (ProductBehavior.INSTALLMENT),
    PAYROLL_LOAN            (ProductBehavior.INSTALLMENT),
    GROUP_LOAN              (ProductBehavior.INSTALLMENT),
    MICRO_LOAN              (ProductBehavior.INSTALLMENT),

    // ── INSTALLMENT — B2B ────────────────────────────────────────────────────
    SME_LOAN                (ProductBehavior.INSTALLMENT);

    public final ProductBehavior behavior;

    ProductType(ProductBehavior behavior) {
        this.behavior = behavior;
    }

    public boolean isRevolving()    { return behavior == ProductBehavior.REVOLVING; }
    public boolean isInstallment()  { return behavior == ProductBehavior.INSTALLMENT; }
}
