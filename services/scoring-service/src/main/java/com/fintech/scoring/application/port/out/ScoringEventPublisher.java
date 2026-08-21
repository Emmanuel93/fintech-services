package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.event.ScoringCompletedEvent;

public interface ScoringEventPublisher {
    void publishCompleted(ScoringCompletedEvent event);
}
