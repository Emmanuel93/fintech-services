package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.application.port.out.ScoreEvaluationRepository;
import com.fintech.scoring.domain.ScoreEvaluation;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class JpaScoreEvaluationAdapter implements ScoreEvaluationRepository {

    private final SpringDataScoreEvaluationRepository jpa;

    JpaScoreEvaluationAdapter(SpringDataScoreEvaluationRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ScoreEvaluation save(ScoreEvaluation evaluation) {
        return jpa.save(evaluation);
    }

    @Override
    public Optional<ScoreEvaluation> findLatestByProspectId(UUID prospectId) {
        return jpa.findFirstByProspectIdOrderByEvaluatedAtDesc(prospectId);
    }
}
