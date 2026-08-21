package com.fintech.scoring.infrastructure.adapter.in.messaging;

import com.fintech.scoring.application.service.ScoringEvaluationService;
import com.fintech.scoring.domain.ScoreEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Phase D — runs the scoring DECISION ENGINE when a product is selected
 * (origination.score-requested), reusing the bureau report prefetched at onboarding.
 * Emits ScoringCompleted carrying the applicationId so origination can correlate directly.
 *
 * <p>Reintento acotado: el prefetch del buró (disparado por {@code prospect-created}) corre async
 * y puede no haber persistido el reporte cuando llega el {@code score-requested} (el usuario elige
 * producto muy rápido tras registrarse). Cada intento es una llamada cross-bean → transacción
 * {@code REQUIRES_NEW} fresca que re-lee el reporte ya commiteado por el prefetch.
 */
@Component
public class ScoreRequestedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ScoreRequestedEventListener.class);

    private final ScoringEvaluationService scoringEvaluationService;
    private final int retryAttempts;
    private final long retryBackoffMs;

    public ScoreRequestedEventListener(
            ScoringEvaluationService scoringEvaluationService,
            @Value("${fintech.scoring.evaluation-retry-attempts:5}") int retryAttempts,
            @Value("${fintech.scoring.evaluation-retry-backoff-ms:600}") long retryBackoffMs) {
        this.scoringEvaluationService = scoringEvaluationService;
        this.retryAttempts = retryAttempts;
        this.retryBackoffMs = retryBackoffMs;
    }

    @KafkaListener(
            topics = "origination.score-requested",
            groupId = "scoring-service",
            containerFactory = "scoreRequestedListenerContainerFactory")
    public void onScoreRequested(@Payload ScoreRequestedPayload payload) {
        log.info("Received ScoreRequested applicationId={} prospectId={} prospectType={} productType={}",
                payload.applicationId(), payload.prospectId(), payload.prospectType(), payload.productType());

        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            Optional<ScoreEvaluation> result = scoringEvaluationService.evaluateForApplication(
                    payload.applicationId(),
                    payload.prospectId(),
                    payload.prospectType(),
                    payload.productType());
            if (result.isPresent()) {
                return;
            }
            if (attempt < retryAttempts) {
                log.info("Evaluation not ready (no report/policy yet) applicationId={} attempt={}/{} — retrying in {}ms",
                        payload.applicationId(), attempt, retryAttempts, retryBackoffMs);
                try {
                    Thread.sleep(retryBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("Scoring could not be evaluated after {} attempts applicationId={} prospectId={} — "
                        + "sin reporte de buró o sin política activa para ({}, {})",
                retryAttempts, payload.applicationId(), payload.prospectId(),
                payload.prospectType(), payload.productType());
    }
}
