package com.fintech.risk.domain;

/**
 * Pure IFRS-9 staging logic (ES-01, RC-04, RC-05). Derives the stage from daysDelinquent using the
 * configurable backstop (30d SICR) and default (90d) thresholds, then applies the forbearance floor
 * and the sticky-down-from-STAGE_3 rule. No side effects, no persistence — trivially unit-testable.
 */
public final class Ifrs9StageResolver {

    private Ifrs9StageResolver() {}

    /**
     * ES-01: {@code days < stage2Threshold} → STAGE_1; {@code < stage3Threshold} → STAGE_2; else STAGE_3.
     * The 30/90-day cuts are the standard IFRS-9/Basel SICR backstop and default presumption.
     */
    public static Ifrs9Stage baseStage(int daysDelinquent, int stage2Threshold, int stage3Threshold) {
        if (daysDelinquent < stage2Threshold) return Ifrs9Stage.STAGE_1;
        if (daysDelinquent < stage3Threshold) return Ifrs9Stage.STAGE_2;
        return Ifrs9Stage.STAGE_3;
    }

    /**
     * The stage the profile should hold, given the raw base stage, its current stage and whether it
     * is still inside a forbearance cure window:
     * <ul>
     *   <li>RC-04: while in cure → floored at STAGE_2 regardless of daysDelinquent;</li>
     *   <li>RC-05: STAGE_3 is sticky downward — from STAGE_3 you can only step down to STAGE_2 (never
     *       straight to STAGE_1) in a single reassessment.</li>
     * </ul>
     */
    public static Ifrs9Stage targetStage(Ifrs9Stage baseStage, Ifrs9Stage currentStage, boolean inCureWindow) {
        Ifrs9Stage candidate = baseStage;
        if (inCureWindow) {
            candidate = Ifrs9Stage.atLeast(candidate, Ifrs9Stage.STAGE_2);
        }
        if (currentStage == Ifrs9Stage.STAGE_3) {
            candidate = Ifrs9Stage.atLeast(candidate, Ifrs9Stage.STAGE_2);
        }
        return candidate;
    }
}
