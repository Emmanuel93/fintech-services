package com.fintech.scoring.domain;

import java.util.UUID;

/** Detalle de la evaluación de una regla individual. Serializado como JSONB en score_evaluations. */
public record RuleEvaluationDetail(
        UUID   ruleId,
        String ruleType,
        String creditType,
        boolean matched,
        int     scoreApplied,
        boolean disqualifying,
        String  detail
) {}
