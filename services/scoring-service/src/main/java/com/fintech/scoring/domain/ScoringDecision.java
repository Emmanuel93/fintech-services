package com.fintech.scoring.domain;

public enum ScoringDecision {
    AUTO_APPROVED,   // riesgo BAJO — aprobación automática
    MANUAL_REVIEW,   // riesgo MEDIO — pasa a revisión humana
    REJECTED         // riesgo ALTO o regla descalificante
}
