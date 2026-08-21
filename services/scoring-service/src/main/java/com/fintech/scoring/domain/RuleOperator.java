package com.fintech.scoring.domain;

import java.math.BigDecimal;

public enum RuleOperator {
    GT, GTE, LT, LTE, EQ;

    /** @return true si {@code value operator threshold} */
    public boolean apply(BigDecimal value, BigDecimal threshold) {
        if (value == null) return false;
        int cmp = value.compareTo(threshold);
        return switch (this) {
            case GT  -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT  -> cmp < 0;
            case LTE -> cmp <= 0;
            case EQ  -> cmp == 0;
        };
    }
}
