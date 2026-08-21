package com.fintech.risk.domain;

/**
 * IFRS-9 impairment stage. Ordinal order matters — STAGE_3 is "worse" than STAGE_1 — and is used
 * by {@link #atLeast} to apply floors (forbearance, sticky-down from STAGE_3).
 */
public enum Ifrs9Stage {
    STAGE_1,  // performing — ECL 12 meses
    STAGE_2,  // SICR (significant increase in credit risk) — ECL lifetime, backstop 30d
    STAGE_3;  // credit-impaired / default — ECL lifetime, presunción rebatible 90d

    /** The worse (higher-ordinal) of the two stages — used to floor a candidate stage. */
    public static Ifrs9Stage atLeast(Ifrs9Stage a, Ifrs9Stage b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
