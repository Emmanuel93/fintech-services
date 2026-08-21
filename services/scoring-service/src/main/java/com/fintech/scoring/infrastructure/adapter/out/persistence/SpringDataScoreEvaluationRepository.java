package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.domain.ScoreEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataScoreEvaluationRepository extends JpaRepository<ScoreEvaluation, UUID> {
    Optional<ScoreEvaluation> findFirstByProspectIdOrderByEvaluatedAtDesc(UUID prospectId);
}
