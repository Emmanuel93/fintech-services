package com.fintech.collections.domain;

/**
 * Derived locally from daysDelinquent — deliberately not shared with Risk (D9), which derives
 * the same buckets from the same raw data independently. See 09_risk_domain.md §Decisiones
 * ("por qué no se comparte un solo cálculo de bucket").
 */
public enum DelinquencyBucket {
    CURRENT, B1_30, B31_60, B61_90, B91_120, B121_180, B181_PLUS;

    public static DelinquencyBucket fromDaysDelinquent(int days) {
        if (days <= 0)   return CURRENT;
        if (days <= 30)  return B1_30;
        if (days <= 60)  return B31_60;
        if (days <= 90)  return B61_90;
        if (days <= 120) return B91_120;
        if (days <= 180) return B121_180;
        return B181_PLUS;
    }

    public boolean isEscalatable() {
        return this == B91_120 || this == B121_180 || this == B181_PLUS;
    }
}
