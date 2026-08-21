package com.fintech.scoring.domain;

public enum RiskLevel {
    BAJO,   // score ≥ umbral alto → decisión AUTO_APPROVED
    MEDIO,  // score en rango medio → MANUAL_REVIEW
    ALTO    // score bajo o descalificado → REJECTED
}
