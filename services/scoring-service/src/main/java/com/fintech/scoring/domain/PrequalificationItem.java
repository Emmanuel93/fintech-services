package com.fintech.scoring.domain;

/** Resultado de precalificación para un tipo de producto. Serializado como JSONB en prequalification_snapshots. */
public record PrequalificationItem(
        String  productType,
        boolean evaluated,
        String  skippedReason,   // NO_BUREAU_REPORT | NO_ACTIVE_POLICY | null si evaluated=true
        String  decision,        // AUTO_APPROVED | MANUAL_REVIEW | REJECTED | null si evaluated=false
        String  riskLevel,       // BAJO | MEDIO | ALTO | null si evaluated=false
        int     totalScore
) {}
