package com.fintech.risk.application.port.out;

import com.fintech.risk.domain.RiskProfile;

/**
 * RiskAssessmentUpdated is published for every ACTIVE profile every night (not only on stage change)
 * — Accounting (T4) needs the provision amount each closing period, since the reserve is a balance,
 * not a discrete event.
 */
public interface RiskEventPublisher {
    void publishRiskAssessmentUpdated(RiskProfile profile, boolean stageChanged);
}
