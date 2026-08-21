package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.ScoreEvaluation;

import java.util.Optional;
import java.util.UUID;

public interface ScoreEvaluationRepository {
    ScoreEvaluation save(ScoreEvaluation evaluation);
    Optional<ScoreEvaluation> findLatestByProspectId(UUID prospectId);
}
