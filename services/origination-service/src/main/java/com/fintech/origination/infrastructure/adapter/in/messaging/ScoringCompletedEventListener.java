package com.fintech.origination.infrastructure.adapter.in.messaging;

import com.fintech.origination.application.ApplyScoringDecisionCommand;
import com.fintech.origination.application.port.in.ApplyScoringDecisionUseCase;
import com.fintech.origination.domain.ProductType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Phase C — consumes scoring decisions and transitions the matching CreditApplication.
 *
 * The current scoring event is prospect-scoped (no applicationId yet — Phase D), so the
 * decision is correlated to the active application by (prospectId, productType).
 */
@Component
public class ScoringCompletedEventListener {

    private static final Logger log = LoggerFactory.getLogger(ScoringCompletedEventListener.class);

    private final ApplyScoringDecisionUseCase applyScoringDecisionUseCase;

    public ScoringCompletedEventListener(ApplyScoringDecisionUseCase applyScoringDecisionUseCase) {
        this.applyScoringDecisionUseCase = applyScoringDecisionUseCase;
    }

    @KafkaListener(
            topics = "scoring.scoring-completed",
            groupId = "origination-service",
            containerFactory = "scoringDecisionListenerContainerFactory")
    public void onScoringCompleted(@Payload ScoringCompletedPayload payload) {
        log.info("Received ScoringCompleted prospectId={} product={} decision={} risk={}",
                payload.prospectId(), payload.productTypeIntent(), payload.decision(), payload.riskLevel());

        ProductType productType;
        try {
            productType = ProductType.valueOf(payload.productTypeIntent());
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("Unknown/absent productType '{}' in ScoringCompleted prospectId={} — skipping",
                    payload.productTypeIntent(), payload.prospectId());
            return;
        }

        applyScoringDecisionUseCase.apply(new ApplyScoringDecisionCommand(
                payload.applicationId(),
                payload.prospectId(),
                productType,
                payload.decision(),
                payload.riskLevel(),
                payload.totalScore(),
                payload.evaluationId(),
                payload.eventId()));
    }
}
