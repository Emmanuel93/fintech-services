package com.fintech.risk.domain;

/**
 * RC-01: derived locally from daysDelinquent with the same table Collections uses — a pure function,
 * deliberately duplicated instead of coupling the two services through a shared "bucket-computed"
 * event (see 09_risk_domain.md §Decisiones).
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
}
