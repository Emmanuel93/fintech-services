package com.fintech.creditproduct.domain;

import java.math.BigDecimal;

/** Comparison operators used in eligibility rules. */
public enum EligibilityOperator {
    EQ  { @Override public boolean apply(BigDecimal v, BigDecimal t) { return v.compareTo(t) == 0; } },
    GT  { @Override public boolean apply(BigDecimal v, BigDecimal t) { return v.compareTo(t) >  0; } },
    GTE { @Override public boolean apply(BigDecimal v, BigDecimal t) { return v.compareTo(t) >= 0; } },
    LT  { @Override public boolean apply(BigDecimal v, BigDecimal t) { return v.compareTo(t) <  0; } },
    LTE { @Override public boolean apply(BigDecimal v, BigDecimal t) { return v.compareTo(t) <= 0; } };

    public abstract boolean apply(BigDecimal value, BigDecimal threshold);
}
