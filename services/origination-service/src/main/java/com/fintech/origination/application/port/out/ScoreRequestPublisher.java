package com.fintech.origination.application.port.out;

import com.fintech.origination.domain.event.ScoreRequestedEvent;

public interface ScoreRequestPublisher {

    void publish(ScoreRequestedEvent event);
}
